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
 * The walk has to be reflective: the editor type is Kotlin-`internal` (so it's matched by FQN) and the whole
 * chain down to the tab is private. On AWB `262.8665.28` that chain is
 * `AgentThreadViewFileEditor.mountedContent` → `ContentSlot.content` → `AgentThreadViewTerminalContent
 * .terminalTab`; an earlier build had the content directly in `activeContent`, i.e. one hop shorter. Rather than
 * hard-code either shape, this does a **bounded breadth-first search over private fields** from the editor,
 * following only `com.intellij.air.*` objects, up to [MAX_DEPTH] hops, and returns the first
 * [AgentThreadViewTerminalTab] it finds. That absorbs a slot/field rename; a *type* rename is reported by
 * [DocentSeamCheck]. A GUI (ACP) content simply has no terminal — that's a null, not a failure.
 */
internal object AwbTerminalTab {

    /** [vf]'s already-built terminal tab in [project], or null when the tab isn't open / has no live terminal. */
    fun find(project: Project, vf: AgentThreadViewVirtualFile): AgentThreadViewTerminalTab? = runCatching {
        val editor = FileEditorManager.getInstance(project).getEditors(vf)
            .firstOrNull { it.javaClass.name == AwbNames.CHAT_FILE_EDITOR_FQN } ?: return null

        // Seed the search with the editor's content fields, in the order AwbNames lists them, so the known
        // shape is tried first; anything else is reached by the generic sweep below.
        var frontier: List<Any> = AwbNames.EDITOR_CONTENT_FIELDS.mapNotNull { readField(editor, it) }
            .ifEmpty { airFields(editor) }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

        repeat(MAX_DEPTH) {
            val next = mutableListOf<Any>()
            for (node in frontier) {
                if (!seen.add(node)) continue
                if (node is AgentThreadViewTerminalTab) return node
                next.addAll(airFields(node))
            }
            if (next.isEmpty()) return null
            frontier = next
        }
        null
    }.getOrNull()

    /** The non-null values of [target]'s declared fields that are themselves workbench objects (or the tab). */
    private fun airFields(target: Any): List<Any> =
        target.javaClass.declaredFields.mapNotNull { f ->
            val value = runCatching { f.apply { isAccessible = true }.get(target) }.getOrNull()
            value?.takeIf { it is AgentThreadViewTerminalTab || it.javaClass.name.startsWith(AIR_PACKAGE) }
        }

    private fun readField(target: Any, name: String): Any? = runCatching {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    }.getOrNull()

    /** Enough hops for editor → slot → content → tab, with one to spare; keeps the sweep bounded. */
    private const val MAX_DEPTH = 4
    private const val AIR_PACKAGE = "com.intellij.air."
}
