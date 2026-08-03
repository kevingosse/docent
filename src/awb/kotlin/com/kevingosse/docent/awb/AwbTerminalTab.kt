package com.kevingosse.docent.awb

import com.intellij.air.frontend.session.view.tui.AgentThreadViewTerminalTab
import com.intellij.air.thread.view.AgentThreadViewVirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project

/**
 * The one place that walks an open thread-view editor down to its **live terminal tab** — used by
 * [DocentEventNotifier] (to type an event into the running agent) and by [WorkbenchSessionDirectory] (to tell a
 * reachable thread from a merely-persisted one).
 *
 * The walk is reflective because the chain is private (and the editor type itself is Kotlin-`internal`, hence
 * matched by FQN): `AgentThreadViewFileEditor` holds the mounted
 * `AgentThreadViewContent` in `activeContent`/`mountedContent`, and the TUI content
 * (`AgentThreadViewTerminalContent`) holds the tab in `terminalTab`. Field names verified against AWB
 * `262.8665.20260723`; a rename is absorbed by the type-driven fallback scan below, and a *type* rename is
 * reported by [DocentSeamCheck]. A GUI (ACP) content simply has no terminal — that's a null, not a failure.
 */
internal object AwbTerminalTab {

    /** [vf]'s already-built terminal tab in [project], or null when the tab isn't open / has no live terminal. */
    fun find(project: Project, vf: AgentThreadViewVirtualFile): AgentThreadViewTerminalTab? = runCatching {
        val editor = FileEditorManager.getInstance(project).getEditors(vf)
            .firstOrNull { it.javaClass.name == AwbNames.CHAT_FILE_EDITOR_FQN } ?: return null
        for (fieldName in AwbNames.EDITOR_CONTENT_FIELDS) {
            val content = readField(editor, fieldName) ?: continue
            readField(content, AwbNames.CONTENT_TERMINAL_TAB_FIELD)?.let { tab ->
                if (tab is AgentThreadViewTerminalTab) return tab
            }
            // The field was renamed: find it by type instead.
            content.javaClass.declaredFields.forEach { f ->
                val value = runCatching { f.apply { isAccessible = true }.get(content) }.getOrNull()
                if (value is AgentThreadViewTerminalTab) return value
            }
        }
        null
    }.getOrNull()

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()
}
