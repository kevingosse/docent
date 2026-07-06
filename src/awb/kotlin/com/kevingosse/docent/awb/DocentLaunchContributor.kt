package com.kevingosse.docent.awb

import com.intellij.air.shared.core.thread.AgentThreadProvider
import com.intellij.air.shared.core.thread.AgentThreadTerminalLaunchSpec
import com.intellij.air.threads.launch.AgentThreadLaunchContributor
import com.intellij.air.threads.launch.McpStreamUrlProvider
import com.intellij.openapi.diagnostic.logger

/**
 * **263 (2026.3) build variant.** Injects the [DocentProtocolPrompt] into an AWB-launched agent's
 * system/base instructions at launch, so the agent knows the Code Review Docent exists and when to use its
 * `docent_*` tools **without the human prompting it**.
 *
 * Registered on the renamed 263 EP `com.intellij.air.threadLaunchContributor` (in the optional, gated
 * `docent-awb.xml`), mirroring the bundled `AirMcpConfigLaunchContributor`. This is the thin EP-interface
 * adapter over the shared [LaunchInjection]; only the interface shape differs from the 262 twin.
 *
 * Ported strictly to AWB-263-API-MAP.md (local-only, not committed) §B.2 / §G:
 *  - The EP interface is now a `fun interface AgentThreadLaunchContributor` whose `contribute(...)` **gained a
 *    `projectDirectory: String?` parameter** (2nd position) and renamed `sessionId`→`threadId`. A regular class
 *    overriding the single method still satisfies a `fun interface`.
 *  - `AgentThreadProvider` has **no `.CLAUDE`/`.CODEX` constants** — we branch on `provider.value`.
 *  - `AgentThreadTerminalLaunchSpec.preallocatedSessionId` → `preallocatedThreadId`.
 *  - `McpStreamUrlProvider.resolve()` is unchanged (only the package moved).
 */
internal class DocentLaunchContributor : AgentThreadLaunchContributor {

    override suspend fun contribute(
        projectPath: String,
        projectDirectory: String?,          // NEW on 263 (map §B.2); Docent doesn't need it — the shared
        provider: AgentThreadProvider,      // injection keys off projectPath, exactly as on 262.
        threadId: String?,                  // renamed from `sessionId` on 262
        launchSpec: AgentThreadTerminalLaunchSpec,
    ): AgentThreadTerminalLaunchSpec {
        return try {
            when (provider.value) {
                AwbNames.PROVIDER_CLAUDE -> {
                    // This thread's id (== the Claude --session-id) is the push target for the one event that still
                    // pushes — the UI-initiated resume. Injected into THIS thread's system prompt as a token the
                    // agent echoes to docent_finalize_trail. Claude WATCHES the EventLog (DeliveryMode.MONITOR).
                    val resolvedThreadId = LaunchInjection.resolveThreadId(threadId, launchSpec.preallocatedThreadId, launchSpec.command)
                    LaunchInjection.registerPushTarget(projectPath, provider.value)
                    LOG.info("Docent: Claude launch — injecting Docent protocol (Monitor delivery, thread=$resolvedThreadId)")
                    launchSpec.copy(
                        command = LaunchInjection.injectDocentMcpConfig(
                            LaunchInjection.injectClaudeSystemPrompt(launchSpec.command, resolvedThreadId),
                            LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                        ),
                    )
                }

                // Codex gets the docent_* tools the same zero-setup way (dotted -c mcp_servers.docent.url +
                // developer_instructions + lifted tool_timeout_sec). It BLOCKS on docent_await_event (AWAIT).
                AwbNames.PROVIDER_CODEX -> {
                    val resolvedThreadId = LaunchInjection.resolveThreadId(threadId, launchSpec.preallocatedThreadId, launchSpec.command)
                    LaunchInjection.registerPushTarget(projectPath, provider.value)
                    LOG.info("Docent: Codex launch — injecting Docent protocol (await delivery, thread=$resolvedThreadId)")
                    launchSpec.copy(
                        command = LaunchInjection.injectCodexConfig(
                            launchSpec.command,
                            resolvedThreadId,
                            LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                        ),
                    )
                }

                else -> launchSpec
            }
        } catch (t: Throwable) {
            // The 263 launch API is @Internal/unstable; never let an injection failure block a launch.
            LOG.warn("Docent: failed to inject the Docent protocol; launching unchanged", t)
            launchSpec
        }
    }

    private companion object {
        private val LOG = logger<DocentLaunchContributor>()
    }
}
