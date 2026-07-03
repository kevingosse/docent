package com.kevingosse.docent.mcp

import com.intellij.mcpserver.McpToolFilter
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A **docent-only** MCP endpoint for coding agents: the IDE's *private* MCP server (a separate port from
 * the user-facing one) with a per-IDE-run auth token and a tool filter that exposes ONLY the `docent_*`
 * tools. [com.kevingosse.docent.awb.DocentLaunchContributor] injects its URL + token into agent launches.
 *
 * Why not point agents at the public IDE MCP server: it's huge (~119 tools), and a user's own client
 * config often already registers it — the injected entry would then expose the whole IDE toolset TWICE
 * under two server names (context bloat + the agent seeing every tool in two places). The filtered
 * endpoint carries exactly the 8 docent tools, so the worst-case overlap is those 8.
 *
 * Bonus: the private server starts independently of the user-facing "Enable MCP server" setting
 * (`McpServerService.authorizedSession` exists precisely so internal integrations don't depend on it),
 * so this removes the last first-run prerequisite — the docent tools are reachable out of the box.
 *
 * Lifecycle: `authorizedSession` is designed to hold the private server up only while its block runs, so
 * the block parks on [awaitCancellation] for the life of this app service's scope (IDE shutdown cancels
 * it). The token is stable for the IDE run; launch injection reads it fresh per launch, so agents
 * relaunched after an IDE restart get the new one.
 */
@Service(Service.Level.APP)
class DocentMcpEndpoint(private val cs: CoroutineScope) {

    /** Where an agent reaches the docent tools: URL + the auth header the private server requires. */
    data class Endpoint(val url: String, val headerName: String, val headerValue: String)

    private val armed = CompletableDeferred<Endpoint?>()
    private val started = AtomicBoolean(false)

    /**
     * The docent-only endpoint, arming it on first use (starts the private server if no one else has).
     * Null when the MCP plugin's private-server API is unavailable or arming failed — callers fall back
     * to the public server URL.
     */
    suspend fun endpoint(): Endpoint? {
        if (started.compareAndSet(false, true)) {
            cs.launch {
                try {
                    McpServerService.getInstance().authorizedSession(
                        McpServerService.McpSessionOptions(
                            commandExecutionMode = McpServerService.AskCommandExecutionMode.RESPECT_GLOBAL_SETTINGS,
                            toolFilter = DocentToolsOnly,
                        ),
                    ) { port, headerName, token ->
                        LOG.info("Docent: docent-only MCP endpoint armed on port $port")
                        armed.complete(Endpoint("http://127.0.0.1:$port/stream", headerName, token))
                        awaitCancellation() // hold the session (server + token) for the IDE's lifetime
                    }
                } catch (t: Throwable) {
                    LOG.warn("Docent: couldn't arm the docent-only MCP endpoint", t)
                } finally {
                    armed.complete(null) // no-op if already armed; resolves waiters on failure
                }
            }
        }
        // First call pays the private-server startup; generous bound so a slow start doesn't false-negative.
        return withTimeoutOrNull(10_000) { armed.await() }
    }

    /** Non-suspending view for status surfaces: the endpoint if it's already armed. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun armedOrNull(): Endpoint? = if (armed.isCompleted) armed.getCompleted() else null

    /**
     * Keyed on the toolset's class FQN prefix — `McpToolFilter.TextMcpToolFilter` matches on
     * `fullyQualifiedName` (= toolset class + "." + method), not the bare `docent_*` tool name.
     * (Implementing the sealed `McpToolFilter` directly isn't possible outside its module;
     * `TextMcpToolFilter` is the extension point left open for that.)
     */
    private object DocentToolsOnly : McpToolFilter.TextMcpToolFilter {
        override fun shouldInclude(toolName: String): Boolean =
            toolName.startsWith("com.kevingosse.docent.")
    }

    companion object {
        private val LOG = logger<DocentMcpEndpoint>()
        fun getInstance(): DocentMcpEndpoint = service()
    }
}
