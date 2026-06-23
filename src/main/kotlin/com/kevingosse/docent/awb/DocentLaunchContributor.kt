package com.kevingosse.docent.awb

import com.intellij.agent.workbench.common.session.AgentSessionProvider
import com.intellij.agent.workbench.sessions.core.launch.AgentSessionLaunchContributor
import com.intellij.agent.workbench.sessions.core.providers.AgentSessionTerminalLaunchSpec
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.kevingosse.docent.DocentReviewService

/**
 * Injects the [DocentProtocolPrompt] into a workbench-launched agent's system/base instructions, so the
 * agent knows the Code Review Docent exists and when to use its `docent_*` tools **without the human
 * prompting it** — transparently, and scoped to agents this IDE launches.
 *
 * Registered on the workbench EP `com.intellij.agent.workbench.sessionLaunchContributor` (in the optional,
 * gated `docent-awb.xml`), mirroring the bundled `AwbMcpConfigContributor`. Fires on every new and resumed
 * launch; returns the [launchSpec] unchanged for any provider we don't (yet) handle.
 *
 * The `docent_*` tools are already reachable by a workbench-launched **Claude** agent — [EnableAwbDirectHttp]
 * flips the registry key 262's `AwbMcpConfigBuilder` reads to wire the IDE's in-process MCP server (`ij`)
 * into Claude's `--mcp-config`. So Claude needs only the instruction; no extra MCP plumbing here.
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
                    // This session's workbench thread id (== the Claude --session-id) is the push target for
                    // review events (see DocentEventNotifier). We do NOT store it globally on the service —
                    // every Claude launch (including background rename/probe launches) would clobber it and the
                    // push would race to the wrong session. Instead we inject it into THIS session's system
                    // prompt as a token; the agent passes it back as docent_finalize_trail's
                    // sessionToken, so the session that arms the review self-identifies as the push target.
                    val threadId = resolveThreadId(sessionId, launchSpec)
                    registerPushTarget(projectPath)
                    launchSpec.copy(command = injectClaudeSystemPrompt(launchSpec.command, threadId))
                }

                // CODEX is deferred: it gets no --mcp-config from the workbench, so it can't reach the
                // docent_* tools today, and injecting "use these tools" instructions for tools it can't
                // see would only mislead it. Wiring Codex needs two confirmations first (the mcp_servers
                // TOML key path/transport, and whether `-c mcp_servers.*` is honored on the interactive
                // TUI). Until then, leave the launch untouched.
                AgentSessionProvider.CODEX -> {
                    LOG.info("Docent: Codex injection deferred (MCP wiring unconfirmed) for ${provider.value}")
                    launchSpec
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
        val protocol = if (threadId != null) DocentProtocolPrompt.withSessionToken(threadId) else DocentProtocolPrompt.TEXT
        val flag = "--append-system-prompt"
        val i = command.indexOf(flag)
        if (i >= 0 && i + 1 < command.size) {
            val merged = command.toMutableList()
            merged[i + 1] = command[i + 1] + "\n\n" + protocol
            return merged
        }

        val insertAt = command.indexOf("--").let { if (it >= 0) it else command.size }
        val result = command.toMutableList()
        result.addAll(insertAt, listOf(flag, protocol))
        return result
    }

    /** This session's workbench thread id (== Claude `--session-id`): the sessionId param, else the spec's
     *  preallocated id, else the `--session-id` already on the command line. Null if none is available. */
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
    private fun registerPushTarget(projectPath: String) {
        val project = resolveProject(projectPath) ?: run {
            LOG.info("Docent: couldn't resolve an open project for $projectPath; the review will use the poll path")
            return
        }
        val service = DocentReviewService.getInstance(project)
        service.agentProjectPath = projectPath
        service.eventNotifier = DocentEventNotifier(project)
    }

    /** Last-resort thread id: the `--session-id <id>` already on the Claude command line. */
    private fun sessionIdFromCommand(command: List<String>): String? {
        val i = command.indexOf("--session-id")
        return if (i >= 0 && i + 1 < command.size) command[i + 1].takeIf { it.isNotBlank() } else null
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
    }
}
