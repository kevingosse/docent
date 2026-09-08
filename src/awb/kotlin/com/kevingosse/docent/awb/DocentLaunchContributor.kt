package com.kevingosse.docent.awb

import com.intellij.air.backend.session.launch.AgentThreadLaunchContributor
import com.intellij.air.backend.session.launch.McpStreamUrlProvider
import com.intellij.air.shared.core.thread.AgentId
import com.intellij.air.shared.core.thread.AgentThreadLaunchSpec
import com.intellij.openapi.diagnostic.logger

/**
 * Injects the [DocentProtocolPrompt] into an Air-launched agent's
 * system/base instructions at launch, so the agent knows the Code Review Docent exists and when to use its
 * `docent_*` tools **without the human prompting it**.
 *
 * **Terminal surface only.** This EP fires for every launch, but its `command` is the *terminal* command line: on
 * the ACP surface (the default Chat route for Claude/Codex since Air 263.x) the spec's command is empty and the
 * agent is started by the ACP adapter — see [DocentAcpMcpServerProvider] + [DocentAcpPromptSupplement] for that
 * path. Both surfaces end up with the same contract: the `docent` MCP entry and the protocol carrying the Air
 * thread id as the sessionToken.
 *
 * Registered on the air.* EP `com.intellij.air.threadLaunchContributor` (in the optional, gated
 * `docent-awb.xml`), mirroring the bundled `AirMcpConfigLaunchContributor`. This is the thin EP-interface
 * adapter over the shared [LaunchInjection].
 *
 * ## One interface generation only
 *
 * `contribute(...)`'s single abstract method is value-class-mangled (`agentId: AgentId` erases to `String`), so
 * its JVM name encodes the signature: `contribute-WeWHIlw` today. Earlier AWB builds had a different mangling
 * (`contribute-QyV-CsE`, with `AgentThreadProvider`/`AgentThreadTerminalLaunchSpec`), and 0.6.x carried both
 * shapes in one class — a hand-mangled twin next to the real `override` — because the interface FQN was stable.
 * The 20260723 layering moved the interface itself (`air.threads.launch` → `air.backend.session.launch`), and a
 * JVM class cannot implement a superinterface that doesn't exist, so spanning generations is no longer possible:
 * this targets the current interface only. [DocentSeamCheck] watches for the next rename and notifies loudly.
 */
internal class DocentLaunchContributor : AgentThreadLaunchContributor {

    override suspend fun contribute(
        projectPath: String,
        projectDirectory: String?,
        agentId: AgentId,
        threadId: String?,
        launchSpec: AgentThreadLaunchSpec,
    ): AgentThreadLaunchSpec {
        // ACP surface (Air 263.x "folded" Claude/Codex): the structured launch spec has NO terminal command — the
        // agent starts through the ACP adapter, and the MCP entry + protocol are delivered by
        // DocentAcpMcpServerProvider / DocentAcpPromptSupplement instead. Appending CLI args here would turn an
        // empty command into a bogus one, so only (re)register the push target and pass the spec through.
        if (launchSpec.command.isEmpty()) {
            if (agentId.value == AwbNames.PROVIDER_CLAUDE || agentId.value == AwbNames.PROVIDER_CODEX) {
                runCatching { LaunchInjection.registerPushTarget(projectPath, agentId.value) }
                    .onFailure { LOG.warn("Docent: couldn't register the push target for an ACP launch", it) }
                LOG.info("Docent: ${agentId.value} ACP launch (thread=${threadId ?: launchSpec.preallocatedThreadId}) — no terminal command; the ACP seams deliver the protocol")
            }
            return launchSpec
        }
        val injected = injectedCommand(projectPath, agentId.value, threadId, launchSpec.command, launchSpec.preallocatedThreadId)
            ?: return launchSpec
        return launchSpec.copy(command = injected)
    }

    /**
     * The shared, version-independent injection: given a launch reduced to plain strings/lists, the new
     * command line with the Docent protocol injected — or null when this launch should pass through
     * unchanged (unsupported agent, or any injection failure: the launch EP is `@Internal`/unstable and
     * must never break a launch).
     */
    private suspend fun injectedCommand(
        projectPath: String,
        providerValue: String,
        threadId: String?,
        command: List<String>,
        preallocatedThreadId: String?,
    ): List<String>? {
        return try {
            when (providerValue) {
                AwbNames.PROVIDER_CLAUDE -> {
                    // This thread's id (== the Claude --session-id) is the push target for the one event that still
                    // pushes — the UI-initiated resume. Injected into THIS thread's system prompt as a token the
                    // agent echoes to docent_finalize_trail. Claude WATCHES the EventLog (DeliveryMode.MONITOR).
                    val resolvedThreadId = LaunchInjection.resolveThreadId(threadId, preallocatedThreadId, command)
                    LaunchInjection.registerPushTarget(projectPath, providerValue)
                    LOG.info("Docent: Claude launch — injecting Docent protocol (Monitor delivery, thread=$resolvedThreadId)")
                    LaunchInjection.injectDocentMcpConfig(
                        LaunchInjection.injectClaudeSystemPrompt(command, resolvedThreadId),
                        LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                    )
                }

                // Codex gets the docent_* tools the same zero-setup way (dotted -c mcp_servers.docent.url +
                // developer_instructions + lifted tool_timeout_sec). It BLOCKS on docent_await_event (AWAIT).
                AwbNames.PROVIDER_CODEX -> {
                    val resolvedThreadId = LaunchInjection.resolveThreadId(threadId, preallocatedThreadId, command)
                    LaunchInjection.registerPushTarget(projectPath, providerValue)
                    LOG.info("Docent: Codex launch — injecting Docent protocol (await delivery, thread=$resolvedThreadId)")
                    LaunchInjection.injectCodexConfig(
                        command,
                        resolvedThreadId,
                        LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                    )
                }

                else -> null
            }
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to inject the Docent protocol; launching unchanged", t)
            null
        }
    }

    private companion object {
        private val LOG = logger<DocentLaunchContributor>()
    }
}
