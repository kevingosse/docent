package com.kevingosse.docent.awb

/**
 * The reflective-FQN + agent-vocabulary constants for the Agent Workbench **air.\* API**, as of the layered
 * rework in AWB `262.8665.20260723`: the flat `com.intellij.air.threads.*` / `com.intellij.air.prompt.core.*`
 * namespaces were split into `air.backend.*` / `air.frontend.*` / `air.shared.*` layers (see
 * docs/AWB-2026.3-COMPAT.md). Only the handful of names still reached **by reflection** live here — everything
 * else is a normal compile-time reference now that the seam targets exactly one AWB generation.
 *
 * The thread-view types (vfile + editor) kept their `com.intellij.air.thread.view.*` package across the rework;
 * the terminal tab moved into the frontend TUI layer.
 */
internal object AwbNames {
    /** The thread-view virtual file (open-tab thread identity). Referenced statically by the seams; the FQN is
     *  kept for [DocentSeamCheck]'s reflective probe. */
    const val CHAT_VFILE_FQN = "com.intellij.air.thread.view.AgentThreadViewVirtualFile"

    /** The thread-view file editor. Its terminal hangs off a private content abstraction
     *  (`activeContent`/`mountedContent`), which is the one thing the seams still reach reflectively. */
    const val CHAT_FILE_EDITOR_FQN = "com.intellij.air.thread.view.AgentThreadViewFileEditor"

    /** The terminal-tab type carrying `sendText(text, shouldExecute, useBracketedPasteMode)`.
     *  Pre-20260723: `com.intellij.air.thread.tui.frontend.AgentThreadViewTerminalTab`. */
    const val TERMINAL_TAB_FQN = "com.intellij.air.frontend.session.view.tui.AgentThreadViewTerminalTab"

    /** The private [CHAT_FILE_EDITOR_FQN] fields holding an `AgentThreadViewContent`, newest-first. */
    val EDITOR_CONTENT_FIELDS = listOf("activeContent", "mountedContent")

    /** The private `AgentThreadViewTerminalContent` field holding the live [TERMINAL_TAB_FQN]. */
    const val CONTENT_TERMINAL_TAB_FIELD = "terminalTab"

    /** Agent ids (`AgentId.value`) the Docent can drive. */
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_CODEX = "codex"
}
