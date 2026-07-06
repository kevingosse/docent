package com.kevingosse.docent.awb

import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.ai.agent.core.session.AgentSessionProvider
import com.intellij.platform.ai.agent.sessions.core.launch.AgentSessionLaunchContributor
import com.intellij.platform.ai.agent.sessions.core.launch.McpStreamUrlProvider
import com.intellij.platform.ai.agent.sessions.core.providers.AgentSessionTerminalLaunchSpec

/**
 * **262 build variant.** Injects the [DocentProtocolPrompt] into a workbench-launched agent's system/base
 * instructions at launch, so the agent knows the Code Review Docent exists and when to use its `docent_*`
 * tools **without the human prompting it** — transparently, scoped to agents this IDE launches.
 *
 * Registered on the workbench EP `com.intellij.agent.workbench.sessionLaunchContributor` (in the optional,
 * gated `docent-awb.xml`), mirroring the bundled `AwbMcpConfigContributor`. Fires on every new and resumed
 * launch; returns the [launchSpec] unchanged for any provider we don't (yet) handle.
 *
 * This class is now the **thin EP-interface adapter**: it unpacks the workbench's `AgentSession*` launch
 * objects into plain strings and hands them to the shared [LaunchInjection] (the AWB-free command-line
 * surgery, identical across build variants). The 263 twin overrides the renamed
 * `AgentThreadLaunchContributor` and calls the same shared code.
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
                    // store it globally on the service; instead we inject it into THIS session's system prompt as a
                    // token the agent echoes to docent_finalize_trail. Claude reaches reviewer actions by WATCHING
                    // the EventLog file (DeliveryMode.MONITOR), so no MCP_TOOL_TIMEOUT env hack is needed.
                    val threadId = LaunchInjection.resolveThreadId(sessionId, launchSpec.preallocatedSessionId, launchSpec.command)
                    LaunchInjection.registerPushTarget(projectPath, provider.value)
                    LOG.info("Docent: Claude launch — injecting Docent protocol (Monitor delivery, thread=$threadId)")
                    launchSpec.copy(
                        command = LaunchInjection.injectDocentMcpConfig(
                            LaunchInjection.injectClaudeSystemPrompt(launchSpec.command, threadId),
                            LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                        ),
                    )
                }

                // Codex gets the docent_* tools the same zero-setup way as Claude via injectCodexConfig (dotted
                // -c mcp_servers.docent.url + developer_instructions + tool_timeout_sec). It has no background-watch
                // tool, so it BLOCKS on docent_await_event (DeliveryMode.AWAIT) — hence the lifted tool_timeout_sec.
                AgentSessionProvider.CODEX -> {
                    val threadId = LaunchInjection.resolveThreadId(sessionId, launchSpec.preallocatedSessionId, launchSpec.command)
                    LaunchInjection.registerPushTarget(projectPath, provider.value)
                    LOG.info("Docent: Codex launch — injecting Docent protocol (await delivery, thread=$threadId)")
                    launchSpec.copy(
                        command = LaunchInjection.injectCodexConfig(
                            launchSpec.command,
                            threadId,
                            LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                        ),
                    )
                }

                else -> launchSpec
            }
        } catch (t: Throwable) {
            // The workbench launch API is @Internal/unstable; never let an injection failure block a launch.
            LOG.warn("Docent: failed to inject the Docent protocol; launching unchanged", t)
            launchSpec
        }
    }

    private companion object {
        private val LOG = logger<DocentLaunchContributor>()
    }
}
