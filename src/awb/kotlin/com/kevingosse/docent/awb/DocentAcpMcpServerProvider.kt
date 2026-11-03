package com.kevingosse.docent.awb

import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.McpServer
import com.intellij.air.acp.AcpMcpServerContribution
import com.intellij.air.acp.AcpMcpServerProvider
import com.intellij.air.acp.AcpMcpServerToolsReader
import com.intellij.air.acp.AcpMcpToolInfo
import com.intellij.air.acp.api.AcpAgentId
import com.intellij.air.backend.session.api.SessionRef
import com.intellij.air.backend.session.launch.McpStreamUrlProvider
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.eel.EelDescriptor
import kotlinx.coroutines.CoroutineScope
import java.lang.reflect.Constructor
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contributes the Docent's MCP endpoint to every Air **ACP** session of an agent the Docent can drive — the
 * ACP-surface counterpart of the `--mcp-config` injection [DocentLaunchContributor] does on the Terminal surface.
 *
 * Registered on `com.intellij.air.acp.mcpServerProvider` (gated `docent-awb.xml`), next to Air's own
 * `AcpIdeMcpServerProviderImpl` that adds `jetbrains_air_ide`. The list returned here lands in the ACP
 * `session/new` (or `session/load`) `mcpServers` array; the `claude-acp` adapter maps `http`/`sse` entries straight
 * into the Claude Agent SDK's MCP config, so the `docent_*` tools are visible to the agent with zero user config.
 *
 * The endpoint is the docent-only authenticated HTTP server ([LaunchInjection.resolveDocentMcp]). Its auth header
 * and the session header (the Air thread id) travel as ACP `HttpHeader`s. Failing to arm it falls back to Air's
 * public IDE MCP URL, and to nothing at all (empty list, never an exception — Air logs and skips a failing
 * provider, but let's not rely on that).
 *
 * Side effect, mirroring the terminal path: the project's push target is (re)registered so a review can reach this
 * thread.
 */
internal class DocentAcpMcpServerProvider : AcpMcpServerProvider {

    // 263.7351: Air passes the session's EelDescriptor (where the agent runs). The Docent endpoint is the IDE's own
    // loopback HTTP server either way, so it's unused here.
    override suspend fun getMcpServers(
        projectDir: Path,
        sessionScope: CoroutineScope,
        sessionRef: SessionRef,
        agentId: AcpAgentId,
        mcpCapabilities: McpCapabilities,
        agentEelDescriptor: EelDescriptor,
    ): List<AcpMcpServerContribution> = contribute(projectDir, sessionRef, agentId, mcpCapabilities)

    /**
     * Bridge for the pre-263.7351 Air shape (5 params, no EelDescriptor; e.g. Rider 263.7031). Not an override here,
     * but on an older Air its `invokeinterface` resolves to this method by name + descriptor, so the ACP surface
     * keeps working there too. Remove once no supported IDE ships the old shape.
     */
    @Suppress("unused")
    suspend fun getMcpServers(
        projectDir: Path,
        sessionScope: CoroutineScope,
        sessionRef: SessionRef,
        agentId: AcpAgentId,
        mcpCapabilities: McpCapabilities,
    ): List<AcpMcpServerContribution> = contribute(projectDir, sessionRef, agentId, mcpCapabilities)

    private suspend fun contribute(
        projectDir: Path,
        sessionRef: SessionRef,
        agentId: AcpAgentId,
        mcpCapabilities: McpCapabilities,
    ): List<AcpMcpServerContribution> {
        return try {
            val provider = AcpInjection.providerForAcpAgent(agentId.rawId) ?: return emptyList()
            // 263.5712: Air now passes the agent's advertised MCP transports. The Docent endpoint is HTTP-only, so
            // an agent that can't speak HTTP MCP gets nothing rather than an entry it would reject.
            if (!mcpCapabilities.http) {
                LOG.info("Docent: ACP $provider agent ${agentId.rawId} doesn't advertise HTTP MCP support; not contributing the '${AcpInjection.DOCENT_MCP_NAME}' server")
                return emptyList()
            }
            // 263.5096: SessionRef lost `threadId`; the thread id is the local session id's value (Air's own
            // AcpSessionRuntime derives it the same way, via SessionIdsKt.getSessionId).
            val threadId = sessionRef.localSessionId.value
            LaunchInjection.registerPushTarget(projectDir.toString(), provider)
            val mcp = LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() } ?: return emptyList()
            LOG.info("Docent: ACP $provider session (thread=$threadId) — contributing the '${AcpInjection.DOCENT_MCP_NAME}' MCP server")
            val headers = LaunchInjection.mcpHeaders(threadId, mcp).map { (name, value) -> HttpHeader(name, value) }
            listOf(newContribution(McpServer.Http(AcpInjection.DOCENT_MCP_NAME, mcp.url, headers)))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            LOG.warn("Docent: failed to contribute the docent MCP server to an ACP session; the agent launches without it", t)
            // Without this server the agent simply has no docent tools and the user sees nothing wrong but "the agent
            // can't find the tools". Say so, once per IDE run.
            if (failureReported.compareAndSet(false, true)) {
                DocentWorkbenchSetup.notify(
                    null,
                    "Agents won't see the Docent tools",
                    "Docent couldn't add its tools to an Air agent session (${t.javaClass.simpleName}: ${t.message}). " +
                        "The agent can't create or run a review. An Air update likely changed its API; check idea.log " +
                        "and update the Docent plugin.",
                    NotificationType.ERROR,
                )
            }
            emptyList()
        }
    }

    internal companion object {
        private val LOG = logger<DocentAcpMcpServerProvider>()
        private val failureReported = AtomicBoolean(false)

        private val NO_TOOLS = object : AcpMcpServerToolsReader {
            override suspend fun read(): List<AcpMcpToolInfo> = emptyList()
        }

        /**
         * The `AcpMcpServerContribution` constructor the Docent calls, or null if this Air has none that fits.
         *
         * Called by reflection because its shape keeps changing under the same since-build: 263.6460 had
         * `(server, tools)`; 263.7786 / 271.2039 replaced it with `(server, tools, skillToolCandidates, guidance,
         * proxyTarget)` (plus an internal one with an `isGoverned` boolean). A compiled call binds to one exact shape
         * and dies with NoSuchMethodError on the other. So: the shortest public constructor that starts with
         * `(McpServer, AcpMcpServerToolsReader)` and whose remaining parameters are all nullable references. That skips
         * the internal `isGoverned` one (a primitive) and Kotlin's synthetic defaults one (an `int` mask).
         * [DocentSeamCheck] probes this same lookup.
         */
        internal fun contributionConstructor(): Constructor<*>? =
            AcpMcpServerContribution::class.java.constructors
                .filter { ctor ->
                    val types = ctor.parameterTypes
                    types.size >= 2 && types[0] == McpServer::class.java &&
                        types[1] == AcpMcpServerToolsReader::class.java &&
                        types.drop(2).none { it.isPrimitive || it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
                }
                .minByOrNull { it.parameterCount }

        /** One server with no tools to report (only Air's MCP debug view reads them) and every newer field left null. */
        private fun newContribution(server: McpServer): AcpMcpServerContribution {
            val ctor = contributionConstructor()
                ?: error("no AcpMcpServerContribution(McpServer, AcpMcpServerToolsReader, …) constructor in this Air")
            val args = arrayOfNulls<Any>(ctor.parameterCount)
            args[0] = server
            args[1] = NO_TOOLS
            return ctor.newInstance(*args) as AcpMcpServerContribution
        }
    }
}
