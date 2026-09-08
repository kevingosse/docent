package com.kevingosse.docent.awb

import com.kevingosse.docent.deliveryModeForProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * The **Air-free** heart of the ACP-surface injection path — the counterpart of [LaunchInjection] for agents Air
 * launches through an ACP adapter instead of a terminal command line.
 *
 * Since Air 263.x, Claude and Codex are "folded" ACP agents: the default Chat surface starts them via the
 * `claude-acp` / `codex-acp` adapters with an EMPTY terminal command, so there is no command line to inject
 * `--append-system-prompt` / `--mcp-config` into. Air offers two ACP-side seams instead, and the Docent uses both
 * (thin adapters over in `src/awb`):
 *  - `AcpMcpServerProvider` (fires at `session/new` / `session/load`): contributes the docent-only MCP endpoint as
 *    an HTTP MCP server, exactly the way Air adds its own `jetbrains_air_ide` entry — so the `docent_*` tools are
 *    visible with zero user config.
 *  - `AcpPromptSupplement` (fires on every outgoing turn, the first one included): appends wire-only text to the
 *    delivered prompt. There is no per-session system-prompt hook (Air doesn't forward `_meta.systemPrompt`), so
 *    the protocol rides the FIRST turn of each thread in full, and later turns carry a one-line reminder so the
 *    contract survives context compaction.
 *
 * Both seams see the Air thread id (`SessionRef.threadId` == the supplement context's `threadId` == the
 * thread-view tab's id), which is the sessionToken the agent echoes back to `docent_finalize_trail`, exactly as
 * on the terminal surface. This object remembers which agent each ACP thread runs, because the supplement
 * context carries no agent id and the protocol's delivery tail differs per provider (Claude watches the
 * EventLog with Monitor, Codex blocks on `docent_await_event`).
 */
internal object AcpInjection {

    /** The MCP server name the Docent contributes for its own endpoint — same name on both surfaces. */
    const val DOCENT_MCP_NAME: String = "docent"

    /** ACP thread id → provider value (`"claude"` / `"codex"`), learned when the session's MCP servers were built. */
    private val providerByThread = ConcurrentHashMap<String, String>()

    /** ACP thread ids that already received the full protocol in this IDE run. */
    private val introduced = ConcurrentHashMap.newKeySet<String>()

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

    /** Remember which provider drives [threadId]. Called from the MCP-server seam at session creation. */
    fun rememberThread(threadId: String, providerValue: String) {
        if (threadId.isNotBlank()) providerByThread[threadId] = providerValue
    }

    /** The provider remembered for [threadId], or null if this IDE run never built its MCP servers. */
    fun providerOf(threadId: String): String? = providerByThread[threadId]

    /**
     * The text to append to the turn going out on [threadId] for [providerValue]: the full Docent protocol (with
     * the thread id baked in as the sessionToken) until [markIntroduced] has been called for the thread, then a
     * short reminder. ACP delivers the prompt as JSON, so unlike the command line the multi-line protocol passes
     * through intact — no [LaunchInjection.singleLine] flattening here.
     */
    fun turnSupplement(threadId: String, providerValue: String): String {
        val mode = deliveryModeForProvider(providerValue)
        if (threadId !in introduced) {
            return "[Code Review Docent — IDE instructions]\n" + DocentProtocolPrompt.forDelivery(mode, threadId)
        }
        return "[Code Review Docent] Reminder: the docent_* tools are still available (search for them by name if " +
            "they're not in your tool list). Keep recording decisions with docent_record_decision (sessionToken " +
            "$threadId); present a review ONLY when the user asks."
    }

    /** Record that [threadId] has been handed the full protocol (call once the turn is handed to the transport). */
    fun markIntroduced(threadId: String) {
        introduced += threadId
    }

    /** Whether [threadId] still awaits its full-protocol turn. */
    fun needsIntroduction(threadId: String): Boolean = threadId !in introduced
}
