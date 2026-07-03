package com.kevingosse.docent.mcp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId

/**
 * Whether the `docent_*` tools are actually reachable by coding agents right now. Normally they are,
 * with nothing to configure: [DocentMcpEndpoint] serves them on the IDE's private MCP server,
 * independent of the user-facing "Enable MCP server" setting. That setting only matters as the
 * FALLBACK path (public-server injection) when the endpoint couldn't arm — and when neither is
 * available the plugin doesn't fail, it just silently does nothing, the worst possible first-run
 * experience. This is the single source of truth the onboarding surfaces (nav-panel notice, startup
 * balloon) key off.
 */
enum class McpPrereqStatus {
    /** The docent endpoint is armed, or the public MCP server is enabled — agents can reach the tools. */
    OK,

    /** The endpoint isn't armed and the server setting is off. Enabling the server is the one-click fix. */
    DISABLED,

    /** The `com.intellij.mcpServer` plugin is missing or disabled — nothing we can enable. */
    UNAVAILABLE,
}

object McpPrereq {
    private val LOG = logger<McpPrereq>()

    fun status(): McpPrereqStatus {
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.intellij.mcpServer"))
        if (plugin == null || !plugin.isEnabled) return McpPrereqStatus.UNAVAILABLE
        return try {
            if (DocentMcpEndpoint.getInstance().armedOrNull() != null || McpServerGate.isEnabled()) McpPrereqStatus.OK
            else McpPrereqStatus.DISABLED
        } catch (t: Throwable) {
            // Present-but-unreachable (optional-depends resolution quirk, or the reflective seam broke on
            // an MCP plugin update): treat as unavailable rather than crash the surface that asked.
            LOG.warn("Docent: MCP server plugin present but its API is unreachable", t)
            McpPrereqStatus.UNAVAILABLE
        }
    }

    /** Turn the MCP server on (setting + running server). Returns false if that failed; callers re-check [status]. */
    fun enableAndStart(): Boolean = try {
        McpServerGate.enableAndStart()
        true
    } catch (t: Throwable) {
        LOG.warn("Docent: failed to enable the MCP server", t)
        false
    }
}

/**
 * The MCP plugin's settings/service seam, reached by reflection: `McpServerSettings` is Kotlin-`internal`
 * (its JVM signatures are public — verified via javap — but the Kotlin compiler refuses a direct call), and
 * reflection also keeps the platform-clean core from ever linking against the optional plugin. Every entry
 * point throws when the plugin is absent or its API changed; [McpPrereq] catches and degrades.
 */
private object McpServerGate {

    fun isEnabled(): Boolean = settingsState().let {
        it.javaClass.getMethod("getEnableMcpServer").invoke(it) as Boolean
    }

    fun enableAndStart() {
        settingsState().let {
            it.javaClass.getMethod("setEnableMcpServer", Boolean::class.javaPrimitiveType).invoke(it, true)
        }
        // Flipping the persisted setting doesn't start the already-created app service; poke it directly.
        val serviceClass = Class.forName("com.intellij.mcpserver.impl.McpServerService")
        val companion = serviceClass.getField("Companion").get(null)
        val service = companion.javaClass.getMethod("getInstance").invoke(companion)
        serviceClass.getMethod("start").invoke(service)
    }

    private fun settingsState(): Any {
        val settingsClass = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
        val settings = settingsClass.getMethod("getInstance").invoke(null)
        return settingsClass.getMethod("getState").invoke(settings)
    }
}
