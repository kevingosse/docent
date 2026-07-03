package com.kevingosse.docent.mcp

import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolInvocationMode
import com.intellij.mcpserver.impl.McpServerService
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Keeps the `docent_*` tools OFF the IDE's **public** MCP server.
 *
 * Why this exists: [DocentMcpToolset] is registered via the standard `com.intellij.mcpServer.mcpToolset`
 * extension point (with `alwaysIncluded()=true`), so the platform publishes it to *every* MCP session —
 * including the user-facing public server, which is registered **globally** and is reachable by coding
 * agents running OUTSIDE this IDE. Those agents would then see the docent tools and could drive the wrong
 * docent instance. The docent tools are meant to travel on exactly one channel: the private, per-IDE-run
 * [DocentMcpEndpoint], whose own session `toolFilter` ([DocentMcpEndpoint.DocentToolsOnly]) already keeps
 * *only* the docent tools. This provider is the missing symmetric half — it hides them everywhere else.
 *
 * How: the platform runs every `McpToolFilterProvider` (this EP) during `McpServerService.getMcpToolsFiltered`,
 * AFTER the session's `toolFilter` has built the candidate set, letting a provider flip each tool's state.
 * We disable the docent tools on every session EXCEPT the private one, which we recognize by its session
 * `toolFilter` being the [DocentMcpEndpoint.DocentToolsOnly] marker — on that session the candidate set is
 * already docent-only, so touching it would empty the private server.
 *
 * Registered in `docent-mcp.xml`, so (like the toolset itself) it only loads when the MCP server plugin is
 * present; the platform-clean core never links against the optional module.
 */
class DocentToolExclusionProvider : McpToolFilterProvider {

    override fun applyFilters(
        context: McpToolFilterProvider.McpToolFilterContext,
        clientInfo: Implementation?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ) {
        // The private docent-only endpoint identifies itself by this exact filter instance; leave it alone.
        if (sessionOptions?.toolFilter is DocentMcpEndpoint.DocentToolsOnly) return
        // Every other session is a public one — turn the docent tools off so out-of-IDE agents never see them.
        context.updateState(enabled = false, routerOnly = null) { tool ->
            tool.descriptor.fullyQualifiedName.startsWith(DocentMcpEndpoint.DocentToolsOnly.TOOL_FQN_PREFIX)
        }
    }

    // The docent tool set is static — nothing ever changes which tools this provider hides, so no updates.
    override fun getUpdates(
        clientInfo: Implementation?,
        scope: CoroutineScope,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ): Flow<Unit> = emptyFlow()
}
