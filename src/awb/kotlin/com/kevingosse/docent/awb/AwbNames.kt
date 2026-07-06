package com.kevingosse.docent.awb

/**
 * The reflective-FQN + provider-vocabulary constants for the Agent Workbench **air.\* API** (2026.2 EAP9+
 * and 2026.3, which share one API — see build.gradle.kts header). That rework moved the reflected
 * chat/terminal types under `com.intellij.air.thread.view.*` and renamed `Session`→`Thread` (see
 * AWB-263-API-MAP.md, local-only, not committed, §C). The inline `262:` notes below record the pre-rework
 * (< 262.8665) FQNs for reference. Centralized here so all reflected names live in one file.
 */
internal object AwbNames {
    /** The thread-view virtual file (open-tab thread identity), read reflectively.
     *  262: `…agent.workbench.chat.AgentChatVirtualFile`. */
    const val CHAT_VFILE_FQN = "com.intellij.air.thread.view.AgentThreadViewVirtualFile"

    /** The thread-view file editor. NB (map §C.2): its `tab` field is GONE on 263 — the editor now holds a
     *  content/surface abstraction (`contentHost`/`activeContent`). 262: `…chat.AgentChatFileEditor`. */
    const val CHAT_FILE_EDITOR_FQN = "com.intellij.air.thread.view.AgentThreadViewFileEditor"

    /** The terminal-tab type carrying `sendText(text, shouldExecute, useBracketedPasteMode)` (map §C.3). On 263
     *  the editor→tab traversal is UNVERIFIED, but the sendText target + its 3-arg signature are confirmed on
     *  this type, so the seam check can at least confirm the method still exists. */
    const val TERMINAL_TAB_FQN = "com.intellij.air.thread.tui.frontend.AgentThreadViewTerminalTab"

    /** Provider `.value` strings the Docent can drive (263 uses `AgentThreadProvider.from(...)`, no constants). */
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_CODEX = "codex"
}
