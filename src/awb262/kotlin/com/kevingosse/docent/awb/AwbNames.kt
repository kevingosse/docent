package com.kevingosse.docent.awb

/**
 * The reflective-FQN + provider-vocabulary constants for the **262 (Agent Workbench 2026.2)** build variant.
 * Centralized here so the reflective chat/terminal FQNs live in one place per variant (the 263 twin, in
 * `src/awb263`, points at the renamed `com.intellij.air.*` types). The compiled AWB types are referenced
 * directly in the seam files; only the reflected ones (which the compiler can't check) are named here.
 */
internal object AwbNames {
    /** The chat virtual file (open-tab session identity), read reflectively — `internal` in the workbench. */
    const val CHAT_VFILE_FQN = "com.intellij.agent.workbench.chat.AgentChatVirtualFile"

    /** The chat file editor whose lazily-built `tab` field holds the live terminal, read reflectively. */
    const val CHAT_FILE_EDITOR_FQN = "com.intellij.agent.workbench.chat.AgentChatFileEditor"

    /** Provider `.value` strings the Docent can drive. */
    const val PROVIDER_CLAUDE = "claude"
    const val PROVIDER_CODEX = "codex"
}
