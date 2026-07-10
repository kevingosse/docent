package com.kevingosse.docent.awb

import com.intellij.air.shared.core.thread.AgentThreadLaunchSpec
import com.intellij.air.shared.core.thread.AgentThreadProvider
import com.intellij.air.shared.core.thread.AgentThreadTerminalLaunchSpec
import com.intellij.air.threads.launch.AgentThreadLaunchContributor
import com.intellij.air.threads.launch.McpStreamUrlProvider
import com.intellij.openapi.diagnostic.logger

/**
 * Injects the [DocentProtocolPrompt] into an AWB-launched agent's
 * system/base instructions at launch, so the agent knows the Code Review Docent exists and when to use its
 * `docent_*` tools **without the human prompting it**.
 *
 * Registered on the air.* EP `com.intellij.air.threadLaunchContributor` (in the optional, gated
 * `docent-awb.xml`), mirroring the bundled `AirMcpConfigLaunchContributor`. This is the thin EP-interface
 * adapter over the shared [LaunchInjection].
 *
 * ## One class, two interface generations
 *
 * AWB 263.1445 changed `AgentThreadLaunchContributor.contribute(...)` incompatibly while keeping the
 * interface FQN: `AgentThreadTerminalLaunchSpec` was renamed to `AgentThreadLaunchSpec` (identical fields)
 * and `provider: AgentThreadProvider` became `agentId: AgentId` (both String value classes). Value-class
 * params feed Kotlin's name mangling, so the single abstract method changed JVM name —
 * `contribute-QyV-CsE` (262.8665..263.1174) → `contribute-WeWHIlw` (263.1445+) — and a class compiled
 * against one build throws [AbstractMethodError] on the other, **failing every thread launch** (that was
 * the 0.5.2-on-263.1445 breakage).
 *
 * The JVM resolves interface methods purely by name + descriptor and only checks "does the receiver have
 * it" at *invoke* time — a class never has to formally implement every abstract method to load. So this one
 * class carries BOTH generations:
 *  - the 262 shape is the real `override` (we compile against the 262.8665 AWB);
 *  - the 263 shape is a hand-mangled backtick-named twin whose descriptor references the compile-only
 *    STUB `AgentThreadLaunchSpec` (src/awbStub — resolves to the real AWB class at runtime) and whose spec
 *    access goes through [SpecReflect] because the stub is memberless.
 * Whichever AWB is installed dispatches to its matching method; the other is inert dead weight.
 * [DocentSeamCheck] watches for a THIRD signature generation and notifies loudly.
 */
internal class DocentLaunchContributor : AgentThreadLaunchContributor {

    /** The 262.8665..263.1174 shape (spec = `AgentThreadTerminalLaunchSpec`) — the real compile-time override. */
    override suspend fun contribute(
        projectPath: String,
        projectDirectory: String?,
        provider: AgentThreadProvider,
        threadId: String?,
        launchSpec: AgentThreadTerminalLaunchSpec,
    ): AgentThreadTerminalLaunchSpec {
        val injected = injectedCommand(projectPath, provider.value, threadId, launchSpec.command, launchSpec.preallocatedThreadId)
            ?: return launchSpec
        return launchSpec.copy(command = injected)
    }

    /**
     * The 263.1445+ shape (spec renamed `AgentThreadLaunchSpec`, provider → `agentId: AgentId`). The name is
     * the exact Kotlin-mangled JVM name of the new interface's abstract method; the spec type is the awbStub
     * placeholder, so the descriptor matches and the JVM dispatches here on 263.1445+. `AgentId` erases to
     * `String`, so the param is declared as the string it is on the wire (its value keeps the same
     * "claude"/"codex" vocabulary). Never an `override` from kotlinc's point of view.
     */
    @Suppress("unused", "FunctionName")
    suspend fun `contribute-WeWHIlw`(
        projectPath: String,
        projectDirectory: String?,
        agentId: String,
        threadId: String?,
        launchSpec: AgentThreadLaunchSpec,
    ): AgentThreadLaunchSpec {
        val spec = launchSpec as Any
        val injected = try {
            injectedCommand(projectPath, agentId, threadId, SpecReflect.command(spec), SpecReflect.preallocatedThreadId(spec))
        } catch (t: Throwable) {
            // The spec's field layout drifted from what SpecReflect expects; never block a launch over it.
            LOG.warn("Docent: couldn't read the 263 launch spec; launching unchanged", t)
            null
        } ?: return launchSpec

        return try {
            @Suppress("USELESS_CAST") // the stub type carries no members; the cast is the whole point
            SpecReflect.withCommand(spec, injected) as AgentThreadLaunchSpec
        } catch (t: Throwable) {
            LOG.warn("Docent: couldn't copy the 263 launch spec; launching unchanged", t)
            launchSpec
        }
    }

    /**
     * The shared, version-independent injection: given a launch reduced to plain strings/lists, the new
     * command line with the Docent protocol injected — or null when this launch should pass through
     * unchanged (unsupported provider, or any injection failure: the launch EP is `@Internal`/unstable and
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

    /**
     * Reflective access to the 263.1445 `AgentThreadLaunchSpec` (a serializable data class whose fields are
     * `@JvmField`-style public fields, identical to 262's `AgentThreadTerminalLaunchSpec`). Reflection because
     * the compile-time type is the memberless awbStub placeholder.
     */
    private object SpecReflect {
        @Suppress("UNCHECKED_CAST")
        fun command(spec: Any): List<String> = spec.javaClass.getField("command").get(spec) as List<String>

        fun preallocatedThreadId(spec: Any): String? =
            spec.javaClass.getField("preallocatedThreadId").get(spec) as? String

        /** A copy of [spec] with [command] swapped in: `copy(newCommand, component2(), component3(), …)` —
         *  a data class's `copy` params are its components in order, and `command` is component1. */
        fun withCommand(spec: Any, command: List<String>): Any {
            val copy = spec.javaClass.methods.first { it.name == "copy" && !it.isSynthetic }
            val args = arrayOfNulls<Any?>(copy.parameterCount)
            args[0] = command
            for (i in 1 until copy.parameterCount) {
                args[i] = spec.javaClass.getMethod("component${i + 1}").invoke(spec)
            }
            return copy.invoke(spec, *args)
        }
    }

    private companion object {
        private val LOG = logger<DocentLaunchContributor>()
    }
}
