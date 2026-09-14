package com.kevingosse.docent.awb

/**
 * The reflective-FQN + agent-vocabulary constants for the Air **air.\* API**, as of the layered
 * namespace (`air.backend.*` / `air.frontend.*` / `air.shared.*`) — currently pinned to the Air bundled in
 * IDEA 263.5096 (see docs/AWB-2026.3-COMPAT.md). Only the handful of names
 * still reached **by reflection** live here — everything else is a normal compile-time reference now that the
 * seam targets exactly one AWB generation.
 *
 * The thread-view vfile kept its `com.intellij.air.thread.view.*` package across the layering; the terminal
 * tab and its live registry live in the frontend TUI layer.
 */
internal object AwbNames {
    /** The thread-view virtual file (open-tab thread identity). Referenced statically by the seams; the FQN is
     *  kept for [DocentSeamCheck]'s reflective probe. */
    const val CHAT_VFILE_FQN = "com.intellij.air.thread.view.AgentThreadViewVirtualFile"

    /** The terminal-tab type carrying `sendText(text, shouldExecute, useBracketedPasteMode)`.
     *  Pre-layering: `com.intellij.air.thread.tui.frontend.AgentThreadViewTerminalTab`. */
    const val TERMINAL_TAB_FQN = "com.intellij.air.frontend.session.view.tui.AgentThreadViewTerminalTab"

    /** The project service that owns every live terminal, keyed by the thread vfile's `tabKey` (263.5096 —
     *  before that, the tab hung off the file editor's private content fields). Kotlin-`internal`, hence
     *  reached by FQN — see [AwbTerminalTab]. */
    const val TERMINAL_REGISTRY_SERVICE_FQN =
        "com.intellij.air.frontend.session.view.tui.AgentThreadViewLiveTerminalRegistryService"

    /** The registry method returning the live entry for a tab key. Kotlin-`internal`, so the JVM name carries
     *  a module suffix (`currentEntry$intellij_air_frontend_session_view_tui` today) — matched by prefix. */
    const val TERMINAL_REGISTRY_ENTRY_METHOD_PREFIX = "currentEntry"

    /** The entry's tab accessor (`AgentThreadViewLiveTerminalEntry.getTab()`). */
    const val TERMINAL_ENTRY_TAB_GETTER = "getTab"

    /** Agent ids (`AgentId.value`) the Docent can drive. */
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_CODEX = "codex"
}
