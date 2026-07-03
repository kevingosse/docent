package com.kevingosse.docent.awb

import com.intellij.platform.ai.agent.core.session.AgentSessionProvider
import com.intellij.platform.ai.agent.sessions.core.launch.McpStreamUrlProvider
import com.intellij.platform.ai.agent.sessions.core.launch.AgentSessionLaunchContributor
import com.intellij.platform.ai.agent.sessions.core.providers.AgentSessionTerminalLaunchSpec
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.kevingosse.docent.DeliveryMode
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.deliveryModeForProvider
import com.kevingosse.docent.mcp.DocentMcpEndpoint

/**
 * Injects the [DocentProtocolPrompt] into a workbench-launched agent's system/base instructions, so the
 * agent knows the Code Review Docent exists and when to use its `docent_*` tools **without the human
 * prompting it** — transparently, and scoped to agents this IDE launches.
 *
 * Registered on the workbench EP `com.intellij.agent.workbench.sessionLaunchContributor` (in the optional,
 * gated `docent-awb.xml`), mirroring the bundled `AwbMcpConfigContributor`. Fires on every new and resumed
 * launch; returns the [launchSpec] unchanged for any provider we don't (yet) handle.
 *
 * For **Claude** we also guarantee the `docent_*` tools are reachable with zero user setup: the launch
 * gets an extra `--mcp-config` with an inline JSON entry pointing at the IDE's current MCP URL (see
 * [injectDocentMcpConfig]). Additive on purpose — no `--strict-mcp-config`, no AWB registry keys — so the
 * user's own global/project MCP config keeps working exactly as before.
 */
internal class DocentLaunchContributor : AgentSessionLaunchContributor {

    override suspend fun contribute(
        projectPath: String,
        provider: AgentSessionProvider,
        sessionId: String?,
        launchSpec: AgentSessionTerminalLaunchSpec,
    ): AgentSessionTerminalLaunchSpec {
        return try {
            when (provider) {
                AgentSessionProvider.CLAUDE -> {
                    // This session's workbench thread id (== the Claude --session-id) is the push target for the
                    // one event that still pushes — the UI-initiated resume (see DocentEventNotifier). We do NOT
                    // store it globally on the service — every Claude launch (including background rename/probe
                    // launches) would clobber it and the push would race to the wrong session. Instead we inject
                    // it into THIS session's system prompt as a token; the agent passes it back as
                    // docent_finalize_trail's sessionToken, so the session that arms the review self-identifies.
                    //
                    // Claude reaches reviewer actions by WATCHING the EventLog file (DeliveryMode.MONITOR) — not
                    // by blocking on docent_await_event — so no MCP_TOOL_TIMEOUT env hack is needed: the inbound
                    // path no longer rides an MCP tool call at all.
                    val threadId = resolveThreadId(sessionId, launchSpec)
                    registerPushTarget(projectPath, provider)
                    LOG.info("Docent: Claude launch — injecting Docent protocol (Monitor delivery, thread=$threadId)")
                    launchSpec.copy(
                        command = injectDocentMcpConfig(injectClaudeSystemPrompt(launchSpec.command, threadId), resolveDocentMcp()),
                    )
                }

                // Codex gets the docent_* tools the same zero-setup way as Claude: injectCodexConfig registers
                // Docent's own MCP entry via `-c mcp_servers.docent.url=<IDE URL>` (a dotted -c path CREATES the
                // entry), additive to whatever the user's ~/.codex/config.toml wires. Two differences from
                // Claude, both handled in injectCodexConfig:
                //  1) No --append-system-prompt. The ambient-instruction analog is `-c developer_instructions=…`
                //     (verified: lands as a developer message, non-destructive). So we inject the protocol there.
                //  2) No background-watch tool → Codex BLOCKS on docent_await_event (DeliveryMode.AWAIT). Codex's
                //     default MCP tool-call timeout is 60s, which would kill a quiet await; we lift it per-server
                //     via `-c mcp_servers.<name>.tool_timeout_sec`. (Server-side await never times out; the agent
                //     re-calls on the rare client timeout — see the docent_await_event description.)
                AgentSessionProvider.CODEX -> {
                    val threadId = resolveThreadId(sessionId, launchSpec)
                    registerPushTarget(projectPath, provider)
                    LOG.info("Docent: Codex launch — injecting Docent protocol (await delivery, thread=$threadId)")
                    launchSpec.copy(command = injectCodexConfig(launchSpec.command, threadId, resolveDocentMcp()))
                }

                else -> launchSpec
            }
        } catch (t: Throwable) {
            // The workbench launch API is @Internal/unstable; never let an injection failure block a launch.
            LOG.warn("Docent: failed to inject the Docent protocol; launching unchanged", t)
            launchSpec
        }
    }

    /**
     * Merge the Docent protocol (with this session's [threadId] baked in as its sessionToken, when known)
     * into the Claude CLI's `--append-system-prompt`.
     *
     * The flag is **last-wins, not appended** (empirically, Claude Code 2.1.183: a second occurrence
     * clobbers the first). So when one is already present we MERGE into its value rather than add a second
     * flag (preserving any user- or other-contributor-supplied prompt). Otherwise we insert the flag+value
     * before the prompt separator (the bare double-dash token) — tokens after it are treated as prompt text,
     * and inserting before it is correct regardless of contributor ordering vs the descriptor that appends
     * the prompt. With no separator, append at the end.
     */
    private fun injectClaudeSystemPrompt(command: List<String>, threadId: String?): List<String> {
        val protocol = singleLine(DocentProtocolPrompt.forDelivery(DeliveryMode.MONITOR, threadId))
        val flag = "--append-system-prompt"
        val i = command.indexOf(flag)
        if (i >= 0 && i + 1 < command.size) {
            val merged = command.toMutableList()
            // Join with a space, NOT a newline: the terminal truncates the arg at its first newline (see singleLine).
            merged[i + 1] = command[i + 1] + "  " + protocol
            return merged
        }

        val insertAt = command.indexOf("--").let { if (it >= 0) it else command.size }
        val result = command.toMutableList()
        result.addAll(insertAt, listOf(flag, protocol))
        return result
    }

    /**
     * Inject the Codex equivalents of Claude's launch knobs (see the CODEX branch in [contribute]). `-c`
     * config overrides, inserted before the `--` prompt separator (tokens after `--` are the initial message):
     *  - `developer_instructions=<await protocol>` — the ambient-instruction analog of `--append-system-prompt`.
     *    The value is a raw multi-line string; Codex parses a `-c` value as TOML and falls back to the literal
     *    when that fails, so the protocol text passes through verbatim (no quoting needed). We always set our
     *    own (the workbench injects none), so there's nothing to merge.
     *  - `mcp_servers.$DOCENT_MCP_NAME.url=<endpoint URL>` (+ `.http_headers.<auth header>` when [mcp] is
     *    the authenticated docent-only endpoint) — Codex's analog of Claude's injected `--mcp-config`: a
     *    dotted `-c` path CREATES the server entry (verified with `codex -c mcp_servers.docent.url=… mcp
     *    list`; `http_headers` verified the same way), so the `docent_*` tools are visible with zero user
     *    config, additively to whatever `~/.codex/config.toml` already registers.
     *  - `tool_timeout_sec=<CODEX_TOOL_TIMEOUT_SEC>` on BOTH our entry and the user's
     *    [CODEX_MCP_SERVER_NAME] one — lifts the 60s default that would otherwise kill a blocking
     *    docent_await_event. The user's entry (when present) also exposes the docent tools, and we can't
     *    control which entry Codex resolves a docent_* call through, so both must survive a quiet await.
     */
    private fun injectCodexConfig(command: List<String>, threadId: String?, mcp: DocentMcpTarget?): List<String> {
        val protocol = singleLine(DocentProtocolPrompt.forDelivery(DeliveryMode.AWAIT, threadId))
        val extra = mutableListOf(
            "-c", "mcp_servers.$CODEX_MCP_SERVER_NAME.tool_timeout_sec=$CODEX_TOOL_TIMEOUT_SEC",
            "-c", "developer_instructions=$protocol",
        )
        if (mcp != null) {
            extra += listOf(
                "-c", "mcp_servers.$DOCENT_MCP_NAME.url=${mcp.url}",
                "-c", "mcp_servers.$DOCENT_MCP_NAME.tool_timeout_sec=$CODEX_TOOL_TIMEOUT_SEC",
            )
            if (mcp.headerName != null) {
                extra += listOf("-c", "mcp_servers.$DOCENT_MCP_NAME.http_headers.${mcp.headerName}=${mcp.headerValue}")
            }
        }
        val insertAt = command.indexOf("--").let { if (it >= 0) it else command.size }
        return command.toMutableList().apply { addAll(insertAt, extra) }
    }

    /** Where an injected `docent` MCP entry should point: URL + the auth header the endpoint requires
     *  (header null on the public-server fallback, which is unauthenticated). */
    private data class DocentMcpTarget(val url: String, val headerName: String?, val headerValue: String?)

    /**
     * Resolve the target for the injected `docent` entry, preferring the **docent-only endpoint**
     * ([DocentMcpEndpoint]: the IDE's private MCP server, filtered to just the `docent_*` tools, running
     * regardless of the user-facing "Enable MCP server" setting). Pointing agents at the PUBLIC IDE server
     * instead would expose its full ~119-tool surface — duplicated wholesale when the user's own client
     * config already registers that server, and confusing for the agent (every tool visible twice).
     * The public URL remains only as a fallback when arming the endpoint fails (e.g. the MCP plugin's
     * private-server API changed), and null (skip injection) when even that is unavailable — the nav
     * panel / startup balloon surface that state.
     */
    private suspend fun resolveDocentMcp(): DocentMcpTarget? {
        runCatching { DocentMcpEndpoint.getInstance().endpoint() }
            .onFailure { LOG.warn("Docent: docent-only MCP endpoint unavailable", it) }
            .getOrNull()
            ?.let { return DocentMcpTarget(it.url, it.headerName, it.headerValue) }
        return McpStreamUrlProvider.resolve()?.let { DocentMcpTarget(it, null, null) }
            .also { if (it == null) LOG.info("Docent: no IDE MCP URL at all — launching without docent MCP entry") }
    }

    /**
     * Append `--mcp-config` with an inline JSON entry pointing at [mcp] (the docent-only endpoint, normally),
     * so the `docent_*` tools are visible to the spawned Claude even on a machine with no MCP client config
     * at all. Claude's `--mcp-config <configs...>` accepts JSON strings and merges with every other MCP
     * source as long as `--strict-mcp-config` isn't passed — so this is purely additive: a user's own wiring
     * (e.g. the global entry the workbench's "set up MCP" notification writes) keeps working. Unlike a
     * config-file entry, the URL + token are resolved fresh at every launch, so they can't go stale.
     *
     * Skipped when the command already carries `--strict-mcp-config` (the workbench's own managed wiring —
     * the `agent.workbench.mcp.use.direct.http` registry key — is active and already points at this IDE;
     * a second config would be ignored-or-merged unpredictably under strict semantics).
     */
    private fun injectDocentMcpConfig(command: List<String>, mcp: DocentMcpTarget?): List<String> {
        if (mcp == null || command.contains("--strict-mcp-config")) return command
        val headers = mcp.headerName?.let { ""","headers":{"$it":"${mcp.headerValue}"}""" } ?: ""
        val json = """{"mcpServers":{"$DOCENT_MCP_NAME":{"type":"http","url":"${mcp.url}"$headers}}}"""
        val insertAt = command.indexOf("--").let { if (it >= 0) it else command.size }
        return command.toMutableList().apply { addAll(insertAt, listOf("--mcp-config", json)) }
    }

    /**
     * Collapse a protocol string to a single physical line for command-line injection.
     *
     * The workbench launches agents through the reworked terminal in NON_SHELL mode
     * (`AgentChatTerminalTabSupport`), which **truncates a command-line argument at its first newline** on
     * Windows — VERIFIED against a real Codex session rollout: a multi-line `developer_instructions` value
     * arrived cut at line 1, dropping the entire protocol after the first sentence (the same hazard applies to
     * Claude's `--append-system-prompt`). So any instruction baked onto the command line MUST be one line.
     * The protocol is plain prose/bullets, so joining its lines with spaces reads fine as a single paragraph.
     */
    private fun singleLine(s: String): String =
        s.lines().joinToString(" ") { it.trim() }.replace(Regex(" {2,}"), " ").trim()

    /** This session's workbench thread id: the sessionId param, else the spec's preallocated id, else the id
     *  already on the command line (Claude's `--session-id <id>`, or Codex's `resume <id>`). Null if none. */
    private fun resolveThreadId(sessionId: String?, launchSpec: AgentSessionTerminalLaunchSpec): String? =
        sessionId?.takeIf { it.isNotBlank() }
            ?: launchSpec.preallocatedSessionId?.takeIf { it.isNotBlank() }
            ?: sessionIdFromCommand(launchSpec.command)

    /**
     * Install a [DocentEventNotifier] for the project and record the project path, so the review can PUSH
     * events into the driving agent's thread instead of making it long-poll. The thread itself is identified
     * per-session via the sessionToken the agent echoes (see [contribute]); we only set project-scoped state
     * here (the same for every session in the project, so it's safe for background launches to re-set it).
     * Best-effort: failure just means the review falls back to the (working but token-heavy) poll path.
     */
    private fun registerPushTarget(projectPath: String, provider: AgentSessionProvider) {
        val project = resolveProject(projectPath) ?: run {
            LOG.info("Docent: couldn't resolve an open project for $projectPath; the review will use the poll path")
            return
        }
        val service = DocentReviewService.getInstance(project)
        // Provider-scoped (not session-scoped) state. In practice one agent kind drives a review at a time, so the
        // last launch's provider is the right one when its session then arms the review; the precise per-session
        // target is the sessionToken the agent echoes (see contribute). The UI "Connect agent…" path overrides
        // these authoritatively from the picked session.
        service.agentProvider = provider.value
        service.deliveryMode = deliveryModeForProvider(provider.value) // Claude → MONITOR (watch), Codex → AWAIT (block)
        service.agentProjectPath = projectPath
        service.eventNotifier = DocentEventNotifier(project)
        // Also (re)install the session directory + launcher here, not only in DocentWorkbenchSetup's project-open
        // activity: a launch is concrete proof the workbench is present, so the "Connect agent…" picker is
        // guaranteed to have them after any session launches (independent of the startup activity having run).
        service.sessionDirectory = WorkbenchSessionDirectory(project)
        service.sessionLauncher = WorkbenchAgentLauncher(project)
    }

    /** Last-resort thread id from the command line: Claude's `--session-id <id>`, or Codex's `resume <id>`. */
    private fun sessionIdFromCommand(command: List<String>): String? {
        val claude = command.indexOf("--session-id")
        if (claude >= 0 && claude + 1 < command.size) command[claude + 1].takeIf { it.isNotBlank() }?.let { return it }
        val resume = command.indexOf("resume")
        if (resume >= 0 && resume + 1 < command.size) command[resume + 1].takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    /** Match the launch's projectPath to an open project by base path (path separators / case normalized). */
    private fun resolveProject(projectPath: String) =
        ProjectManager.getInstance().openProjects.firstOrNull { p ->
            val base = p.basePath ?: return@firstOrNull false
            normalizePath(base) == normalizePath(projectPath)
        }

    private fun normalizePath(p: String): String = p.replace('\\', '/').trimEnd('/').lowercase()

    companion object {
        private val LOG = logger<DocentLaunchContributor>()

        /** The server name Docent injects for its own per-launch IDE-MCP entry (Claude `--mcp-config` /
         *  Codex `-c mcp_servers.<name>.url`). Tool visibility never depends on the user's own config. */
        private const val DOCENT_MCP_NAME = "docent"

        /**
         * The MCP server name a user's own `~/.codex/config.toml` typically gives THIS IDE's MCP server
         * (`[mcp_servers.<name>] url = …/stream`) — we defensively patch its `tool_timeout_sec` on the Codex
         * command line, because Codex may resolve a docent_* call through that entry rather than our injected
         * [DOCENT_MCP_NAME] one (both expose the same tools). Wrong-or-absent is harmless now that the
         * injected entry guarantees visibility; on this box the user entry is named `rider`.
         */
        private const val CODEX_MCP_SERVER_NAME = "rider"

        /** Per-server MCP tool-call timeout (seconds) for Codex, lifting the 60s default so a blocking
         *  docent_await_event survives a quiet review. 1h is generous; the agent re-calls on the rare timeout. */
        private const val CODEX_TOOL_TIMEOUT_SEC = 3600
    }
}
