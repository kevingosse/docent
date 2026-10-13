package com.kevingosse.docent.awb

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.deliveryModeForProvider
import com.kevingosse.docent.mcp.DocentMcpEndpoint

/**
 * The delicate, **AWB-free** heart of the launch-injection path — extracted from `DocentLaunchContributor`
 * so it lives in the platform-clean `src/main` rather than the AWB-touching `src/awb` seam. Every function
 * here operates on `List<String>` / `String` / the platform-clean `DocentReviewService`, and touches **no**
 * Air type. `DocentLaunchContributor` is just the thin EP-interface adapter: it unpacks the
 * `AgentThread*` launch objects into plain strings, calls in here, and repacks the result.
 *
 * This is the most fragile code in the plugin (command-line surgery whose failure silently breaks the
 * agent handoff or corrupts a launch), so keeping it in a single shared place is deliberate. See
 * `DocentLaunchContributor` for the surrounding flow and the original narration of *why* each knob is
 * injected.
 *
 * NB: `registerPushTarget` news up `DocentEventNotifier` / `WorkbenchSessionDirectory` /
 * `WorkbenchAgentLauncher` — the AWB-touching seams over in `src/awb`. This shared reference stays
 * AWB-free (it only names those classes); no AWB type leaks here.
 */
internal object LaunchInjection {

    private val LOG = logger<LaunchInjection>()

    /** The server name Docent injects for its own per-launch IDE-MCP entry (Claude `--mcp-config` /
     *  Codex `-c mcp_servers.<name>.url`). Tool visibility never depends on the user's own config. */
    private const val DOCENT_MCP_NAME = "docent"

    /** Per-server MCP tool-call timeout (seconds) for Codex, lifting the 60s default so a blocking
     *  docent_await_event survives a quiet review. 1h is generous; the agent re-calls on the rare timeout. */
    private const val CODEX_TOOL_TIMEOUT_SEC = 3600

    /** Where an injected `docent` MCP entry should point: URL + the auth header the endpoint requires
     *  (header null on the public-server fallback, which is unauthenticated). */
    internal data class DocentMcpTarget(val url: String, val headerName: String?, val headerValue: String?)

    /**
     * Inject the Codex equivalent of Claude's `--mcp-config`. `-c` config overrides, inserted before the `--`
     * prompt separator (tokens after `--` are the initial message):
     *  - `mcp_servers.$DOCENT_MCP_NAME.url=<endpoint URL>` plus one `.http_headers.<name>` per [mcpHeaders] entry —
     *    a dotted `-c` path CREATES the server entry, so the `docent_*` tools are visible with zero user
     *    config, additively to whatever `~/.codex/config.toml` already registers.
     *  - `tool_timeout_sec=<CODEX_TOOL_TIMEOUT_SEC>` on our own [DOCENT_MCP_NAME] entry — lifts the 60s
     *    default that would otherwise kill a blocking docent_await_event.
     *
     * **Remote-resume exception:** no `mcp_servers.*` overrides on `codex resume --remote …` commands.
     * Codex (verified standalone on 0.144.1, no IDE involved) deadlocks "Loading MCP (x/y)" forever when
     * the resume *client* carries an `mcp_servers.*` override its app-server doesn't have — and the
     * workbench spawns that app-server itself, with no contributor hook, so the server side can never
     * match. Flag position (before/after the `resume` token) doesn't help; server-side or symmetric
     * config works, client-only freezes. Until Codex fixes the handshake, a resumed tab without docent
     * tools beats a frozen one.
     */
    fun injectCodexConfig(command: List<String>, threadId: String?, mcp: DocentMcpTarget?): List<String> {
        val isRemoteResume = command.contains("resume") && command.contains("--remote")
        if (mcp == null || isRemoteResume) return command
        val extra = mutableListOf(
            "-c", "mcp_servers.$DOCENT_MCP_NAME.url=${mcp.url}",
            "-c", "mcp_servers.$DOCENT_MCP_NAME.tool_timeout_sec=$CODEX_TOOL_TIMEOUT_SEC",
        )
        mcpHeaders(threadId, mcp).forEach { (name, value) ->
            extra += listOf("-c", "mcp_servers.$DOCENT_MCP_NAME.http_headers.$name=$value")
        }
        val insertAt = command.indexOf("--").let { if (it >= 0) it else command.size }
        return command.toMutableList().apply { addAll(insertAt, extra) }
    }

    /**
     * Append `--mcp-config` with an inline JSON entry pointing at [mcp] (the docent-only endpoint, normally),
     * so the `docent_*` tools are visible to the spawned Claude even on a machine with no MCP client config
     * at all. Claude's `--mcp-config <configs...>` accepts JSON strings and merges with every other MCP
     * source as long as `--strict-mcp-config` isn't passed — so this is purely additive.
     *
     * Skipped when the command already carries `--strict-mcp-config` (the workbench's own managed wiring is
     * active and already points at this IDE; a second config would be ignored-or-merged unpredictably under
     * strict semantics).
     */
    fun injectDocentMcpConfig(command: List<String>, threadId: String?, mcp: DocentMcpTarget?): List<String> {
        if (mcp == null || command.contains("--strict-mcp-config")) return command
        val headers = mcpHeaders(threadId, mcp)
        val headersJson = if (headers.isEmpty()) "" else
            headers.joinToString(",", prefix = ",\"headers\":{", postfix = "}") { (name, value) -> "\"$name\":\"$value\"" }
        val json = """{"mcpServers":{"$DOCENT_MCP_NAME":{"type":"http","url":"${mcp.url}"$headersJson}}}"""
        val insertAt = command.indexOf("--").let { if (it >= 0) it else command.size }
        return command.toMutableList().apply { addAll(insertAt, listOf("--mcp-config", json)) }
    }

    /** The headers of an injected `docent` MCP entry: the endpoint's auth header (none on the public-server
     *  fallback), then the session header carrying [threadId], so the IDE knows which session calls a tool. */
    fun mcpHeaders(threadId: String?, mcp: DocentMcpTarget): List<Pair<String, String>> = buildList {
        if (mcp.headerName != null) add(mcp.headerName to (mcp.headerValue ?: ""))
        if (!threadId.isNullOrBlank()) add(DocentMcpEndpoint.SESSION_HEADER to threadId)
    }

    /**
     * This session's workbench thread id: the [sessionId] param, else the spec's [preallocatedId], else the
     * id already on the command line (Claude's `--session-id <id>`, or Codex's `resume <id>`). Null if none.
     *
     * The [preallocatedId] is unpacked by [DocentLaunchContributor] from the air.* launch spec's
     * `preallocatedThreadId`, so this function stays AWB-free.
     */
    fun resolveThreadId(sessionId: String?, preallocatedId: String?, command: List<String>): String? =
        sessionId?.takeIf { it.isNotBlank() }
            ?: preallocatedId?.takeIf { it.isNotBlank() }
            ?: sessionIdFromCommand(command)

    /** Last-resort thread id from the command line: Claude's `--session-id <id>`, or Codex's `resume <id>`. */
    fun sessionIdFromCommand(command: List<String>): String? {
        val claude = command.indexOf("--session-id")
        if (claude >= 0 && claude + 1 < command.size) command[claude + 1].takeIf { it.isNotBlank() }?.let { return it }
        val resume = command.indexOf("resume")
        if (resume >= 0 && resume + 1 < command.size) command[resume + 1].takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /**
     * Resolve the target for the injected `docent` entry, preferring the **docent-only endpoint**
     * ([DocentMcpEndpoint]: the IDE's private MCP server, filtered to just the `docent_*` tools). Pointing
     * agents at the PUBLIC IDE server instead would expose its full ~119-tool surface — duplicated wholesale
     * and confusing for the agent. The public URL (supplied lazily by the variant via [fallbackStreamUrl],
     * which reads the AWB `McpStreamUrlProvider`) remains only as a fallback when arming the endpoint fails,
     * and null (skip injection) when even that is unavailable.
     *
     * [fallbackStreamUrl] is a lambda (not an eager value) so the AWB `McpStreamUrlProvider.resolve()` call
     * only runs when the docent-only endpoint is unavailable — preserving the original short-circuit.
     */
    suspend fun resolveDocentMcp(fallbackStreamUrl: () -> String?): DocentMcpTarget? {
        runCatching { DocentMcpEndpoint.getInstance().endpoint() }
            .onFailure { LOG.warn("Docent: docent-only MCP endpoint unavailable", it) }
            .getOrNull()
            ?.let { return DocentMcpTarget(it.url, it.headerName, it.headerValue) }
        return fallbackStreamUrl()?.let { DocentMcpTarget(it, null, null) }
            .also { if (it == null) LOG.info("Docent: no IDE MCP URL at all — launching without docent MCP entry") }
    }

    /**
     * Install a [DocentEventNotifier] for the project and record the project path, so the review can PUSH
     * events into the driving agent's thread instead of making it long-poll. The thread itself is identified
     * per-session by the session header on the agent's tool calls; we only set project-scoped state here (the same for
     * every session in the project, so it's safe for background launches to re-set it). Best-effort: failure
     * just means the review falls back to the (working but token-heavy) poll path.
     *
     * [providerValue] is the provider's `.value` string (`"claude"` / `"codex"`), unpacked by the variant so
     * this stays AWB-free.
     */
    fun registerPushTarget(projectPath: String, providerValue: String) {
        val project = resolveProject(projectPath) ?: run {
            LOG.info("Docent: couldn't resolve an open project for $projectPath; the review will use the poll path")
            return
        }
        val service = DocentReviewService.getInstance(project)
        // Provider-scoped (not session-scoped) state. In practice one agent kind drives a review at a time, so the
        // last launch's provider is the right one when its session then arms the review; the precise per-session
        // target comes from the session header on the tool call that opens the review. The UI "Connect agent…" path overrides these authoritatively.
        service.agentProvider = providerValue
        service.deliveryMode = deliveryModeForProvider(providerValue) // Claude → MONITOR (watch), Codex → AWAIT (block)
        service.agentProjectPath = projectPath
        service.eventNotifier = DocentEventNotifier(project)
        // Also (re)install the session directory + launcher here, not only in DocentWorkbenchSetup's project-open
        // activity: a launch is concrete proof the workbench is present, so the "Connect agent…" picker is
        // guaranteed to have them after any session launches (independent of the startup activity having run).
        service.sessionDirectory = WorkbenchSessionDirectory(project)
        service.sessionLauncher = WorkbenchAgentLauncher(project)
    }

    /** Match the launch's projectPath to an open project by base path (path separators / case normalized). */
    private fun resolveProject(projectPath: String) =
        ProjectManager.getInstance().openProjects.firstOrNull { p ->
            val base = p.basePath ?: return@firstOrNull false
            normalizePath(base) == normalizePath(projectPath)
        }

    fun normalizePath(p: String): String = p.replace('\\', '/').trimEnd('/').lowercase()
}
