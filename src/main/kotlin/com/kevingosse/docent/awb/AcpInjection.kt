package com.kevingosse.docent.awb

/**
 * The **Air-free** part of the ACP-surface injection — the counterpart of [LaunchInjection] for agents Air launches
 * through an ACP adapter instead of a terminal command line.
 *
 * Since Air 263.x, Claude and Codex are "folded" ACP agents: the default Chat surface starts them via the
 * `claude-acp` / `codex-acp` adapters with an EMPTY terminal command, so there is no command line to inject
 * `--mcp-config` into. `DocentAcpMcpServerProvider` (in `src/awb`) contributes the docent-only MCP endpoint at
 * `session/new` / `session/load` instead, exactly the way Air adds its own `jetbrains_air_ide` entry, with the Air
 * thread id in the session header.
 */
internal object AcpInjection {

    /** The MCP server name the Docent contributes for its own endpoint — same name on both surfaces. */
    const val DOCENT_MCP_NAME: String = "docent"

    /**
     * Map an ACP agent's raw id (`claude-acp`, `codex-acp`, `codex`, …) to the Docent's provider vocabulary, or
     * null for an agent the Docent can't drive (Junie, OpenCode, custom agents…).
     */
    fun providerForAcpAgent(rawId: String): String? {
        val id = rawId.lowercase()
        return when {
            id.startsWith("claude") -> "claude"
            id.startsWith("codex") -> "codex"
            else -> null
        }
    }
}
