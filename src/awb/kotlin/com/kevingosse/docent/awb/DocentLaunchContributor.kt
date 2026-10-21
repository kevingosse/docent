package com.kevingosse.docent.awb

import com.intellij.air.backend.session.launch.AgentThreadLaunchContributor
import com.intellij.air.backend.session.launch.McpStreamUrlProvider
import com.intellij.air.shared.core.thread.AgentId
import com.intellij.air.shared.core.thread.AgentThreadLaunchSpec
import com.intellij.openapi.diagnostic.logger

/**
 * Adds the `docent` MCP entry to an Air-launched agent's command line, so the `docent_*` tools are there when the
 * user asks for a code review, with no user config. The entry carries the Air thread id in the session header.
 *
 * **Terminal surface only.** This EP fires for every launch, but its `command` is the *terminal* command line: on
 * the ACP surface (the default Chat route for Claude/Codex since Air 263.x) the spec's command is empty and the
 * agent is started by the ACP adapter — see [DocentAcpMcpServerProvider] for that path. Both surfaces end up
 * with the same `docent` MCP entry.
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
        // agent starts through the ACP adapter, and DocentAcpMcpServerProvider delivers the MCP entry instead. Appending CLI args here would turn an
        // empty command into a bogus one, so only (re)register the push target and pass the spec through.
        if (launchSpec.command.isEmpty()) {
            if (agentId.value == AwbNames.PROVIDER_CLAUDE || agentId.value == AwbNames.PROVIDER_CODEX) {
                runCatching { LaunchInjection.registerPushTarget(projectPath, agentId.value) }
                    .onFailure { LOG.warn("Docent: couldn't register the push target for an ACP launch", it) }
                LOG.info("Docent: ${agentId.value} ACP launch (thread=${threadId ?: launchSpec.preallocatedThreadId}) — no terminal command; the ACP MCP provider adds the docent entry")
            }
            return launchSpec
        }
        val injected = injectedCommand(projectPath, agentId.value, threadId, launchSpec.command, launchSpec.preallocatedThreadId)
            ?: return launchSpec
        return runCatching { copyWithCommand(launchSpec, injected) }
            .onFailure { LOG.warn("Docent: couldn't rewrite the launch command; launching unchanged", it) }
            .getOrDefault(launchSpec)
    }

    /**
     * `launchSpec.copy(command = injected)`, but arity-agnostic. A compiled `copy(command = …)` binds to the exact
     * synthetic `copy$default(spec, <every field>, mask, marker)`, and every field Air adds breaks it with
     * NoSuchMethodError (271 added `AgentWriterHosting`). So call `copy$default` reflectively: `command` is the
     * first field (bit 0 of the mask), and every other bit says "keep this field", so the placeholders passed for
     * them are ignored.
     */
    private fun copyWithCommand(spec: AgentThreadLaunchSpec, command: List<String>): AgentThreadLaunchSpec {
        val copy = spec.javaClass.methods.firstOrNull { it.name == "copy\$default" }
            ?: error("AgentThreadLaunchSpec.copy\$default is gone")
        val types = copy.parameterTypes
        // (spec, field_0 … field_{n-1}, mask: Int, marker: Object)
        val fieldCount = types.size - 3
        require(fieldCount in 1..31 && types[1] == List::class.java) { "unexpected copy\$default shape: ${types.toList()}" }
        val args = arrayOfNulls<Any>(types.size)
        args[0] = spec
        args[1] = command
        for (i in 2..fieldCount) args[i] = zeroOf(types[i])
        args[fieldCount + 1] = ((1 shl fieldCount) - 1) and 1.inv()
        return copy.invoke(null, *args) as AgentThreadLaunchSpec
    }

    private fun zeroOf(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        Character.TYPE -> 0.toChar()
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    /**
     * The shared, version-independent injection: given a launch reduced to plain strings/lists, the new
     * command line with the `docent` MCP entry added — or null when this launch should pass through
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
                    // This thread's id (== the Claude --session-id) goes in the session header, so the review
                    // messages THIS thread. Claude WATCHES the EventLog (DeliveryMode.MONITOR).
                    val resolvedThreadId = LaunchInjection.resolveThreadId(threadId, preallocatedThreadId, command)
                    LaunchInjection.registerPushTarget(projectPath, providerValue)
                    LOG.info("Docent: Claude launch — adding the docent MCP entry (Monitor delivery, thread=$resolvedThreadId)")
                    LaunchInjection.injectDocentMcpConfig(
                        command,
                        resolvedThreadId,
                        LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                    )
                }

                // Codex gets the docent_* tools the same zero-setup way (dotted -c mcp_servers.docent.url + the
                // headers + lifted tool_timeout_sec). It BLOCKS on docent_await_event (AWAIT).
                AwbNames.PROVIDER_CODEX -> {
                    val resolvedThreadId = LaunchInjection.resolveThreadId(threadId, preallocatedThreadId, command)
                    LaunchInjection.registerPushTarget(projectPath, providerValue)
                    LOG.info("Docent: Codex launch — adding the docent MCP entry (await delivery, thread=$resolvedThreadId)")
                    LaunchInjection.injectCodexConfig(
                        command,
                        resolvedThreadId,
                        LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() },
                    )
                }

                else -> null
            }
        } catch (t: Throwable) {
            LOG.warn("Docent: failed to add the docent MCP entry; launching unchanged", t)
            null
        }
    }

    private companion object {
        private val LOG = logger<DocentLaunchContributor>()
    }
}
