package com.kevingosse.docent.awb

/**
 * The reflective-FQN + agent-vocabulary constants for the Agent Workbench **air.\* API**, as of the layered
 * namespace (`air.backend.*` / `air.frontend.*` / `air.shared.*`) that AWB `262.8665.28` ships — the
 * release-line build paired with Rider 2026.2.0.1 (see docs/AWB-2026.3-COMPAT.md). Only the handful of names
 * still reached **by reflection** live here — everything else is a normal compile-time reference now that the
 * seam targets exactly one AWB generation.
 *
 * The thread-view types (vfile + editor) kept their `com.intellij.air.thread.view.*` package across the
 * layering; the terminal tab moved into the frontend TUI layer.
 */
internal object AwbNames {
    /** The thread-view virtual file (open-tab thread identity). Referenced statically by the seams; the FQN is
     *  kept for [DocentSeamCheck]'s reflective probe. */
    const val CHAT_VFILE_FQN = "com.intellij.air.thread.view.AgentThreadViewVirtualFile"

    /** The thread-view file editor (Kotlin-`internal`, hence reached by FQN). Its terminal hangs off a private
     *  content abstraction, which is the one thing the seams still reach reflectively — see [AwbTerminalTab]. */
    const val CHAT_FILE_EDITOR_FQN = "com.intellij.air.thread.view.AgentThreadViewFileEditor"

    /** The terminal-tab type carrying `sendText(text, shouldExecute, useBracketedPasteMode)`.
     *  Pre-layering: `com.intellij.air.thread.tui.frontend.AgentThreadViewTerminalTab`. */
    const val TERMINAL_TAB_FQN = "com.intellij.air.frontend.session.view.tui.AgentThreadViewTerminalTab"

    /** The private [CHAT_FILE_EDITOR_FQN] fields that (directly or via a slot) hold the mounted content.
     *  `activeContent` is the pre-`.28` name and is kept as a second candidate — the walk is field-name
     *  agnostic anyway, this list only decides where it starts. */
    val EDITOR_CONTENT_FIELDS = listOf("mountedContent", "activeContent", "contentHost")

    /** Agent ids (`AgentId.value`) the Docent can drive. */
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_CODEX = "codex"
}
