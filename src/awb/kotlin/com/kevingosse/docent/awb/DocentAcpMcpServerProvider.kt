package com.kevingosse.docent.awb

import com.agentclientprotocol.model.HttpHeader
import com.agentclientprotocol.model.McpCapabilities
import com.agentclientprotocol.model.McpServer
import com.intellij.air.acp.AcpMcpServerProvider
import com.intellij.air.acp.api.AcpAgentId
import com.intellij.air.backend.session.api.SessionRef
import com.intellij.air.backend.session.launch.McpStreamUrlProvider
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/**
 * Contributes the Docent's MCP endpoint to every Air **ACP** session of an agent the Docent can drive — the
 * ACP-surface counterpart of the `--mcp-config` injection [DocentLaunchContributor] does on the Terminal surface.
 *
 * Registered on `com.intellij.air.acp.mcpServerProvider` (gated `docent-awb.xml`), next to Air's own
 * `AcpIdeMcpServerProviderImpl` that adds `jetbrains_air_ide`. The list returned here lands in the ACP
 * `session/new` (or `session/load`) `mcpServers` array; the `claude-acp` adapter maps `http`/`sse` entries straight
 * into the Claude Agent SDK's MCP config, so the `docent_*` tools are visible to the agent with zero user config.
 *
 * The endpoint is the docent-only authenticated HTTP server ([LaunchInjection.resolveDocentMcp]); its auth header
 * travels as an ACP `HttpHeader`. Failing to arm it falls back to Air's public IDE MCP URL, and to nothing at all
 * (empty list, never an exception — Air logs and skips a failing provider, but let's not rely on that).
 *
 * Side effects, mirroring the terminal path: the thread's provider is remembered for [DocentAcpPromptSupplement]
 * (the supplement context carries no agent id), and the project's push target is (re)registered so a review can
 * reach this thread.
 */
internal class DocentAcpMcpServerProvider : AcpMcpServerProvider {

    override suspend fun getMcpServers(
        projectDir: Path,
        sessionScope: CoroutineScope,
        sessionRef: SessionRef,
        agentId: AcpAgentId,
        mcpCapabilities: McpCapabilities,
    ): List<McpServer> {
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
            AcpInjection.rememberThread(threadId, provider)
            LaunchInjection.registerPushTarget(projectDir.toString(), provider)
            val mcp = LaunchInjection.resolveDocentMcp { McpStreamUrlProvider.resolve() } ?: return emptyList()
            LOG.info("Docent: ACP $provider session (thread=$threadId) — contributing the '${AcpInjection.DOCENT_MCP_NAME}' MCP server")
            val headers = if (mcp.headerName != null) listOf(HttpHeader(mcp.headerName, mcp.headerValue ?: "")) else emptyList()
            listOf(McpServer.Http(AcpInjection.DOCENT_MCP_NAME, mcp.url, headers))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            LOG.warn("Docent: failed to contribute the docent MCP server to an ACP session; the agent launches without it", t)
            emptyList()
        }
    }

    private companion object {
        private val LOG = logger<DocentAcpMcpServerProvider>()
    }
}
