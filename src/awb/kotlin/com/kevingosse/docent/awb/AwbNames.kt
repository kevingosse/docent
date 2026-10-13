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
     *  263.5096–263.6460: `com.intellij.air.frontend.session.view.tui`; pre-layering: `com.intellij.air.thread.tui.frontend`. */
    const val TERMINAL_TAB_FQN = "com.intellij.air.frontend.session.terminal.AgentThreadViewTerminalTab"

    /** The app service that owns every live terminal, keyed by the thread vfile's `localSessionIdentity`
     *  (263.7029; before that a per-project `AgentThreadViewLiveTerminalRegistryService` keyed by `tabKey`).
     *  Referenced statically by [AwbTerminalTab]; the FQN is kept for [DocentSeamCheck]'s probe. */
    const val TERMINAL_RUNTIME_MANAGER_FQN = "com.intellij.air.frontend.session.terminal.AgentTerminalRuntimeManager"

    /** Agent ids (`AgentId.value`) the Docent can drive. */
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_CODEX = "codex"
}
