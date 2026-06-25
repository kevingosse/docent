package com.kevingosse.docent.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import javax.swing.Icon

/**
 * Marker the [DocentPanel] stamps on a diff request: the mutable, in-session comment threads for that
 * file. Its presence both scopes this (globally-registered) extension to Docent diffs and hands over
 * the shared list, so threads survive switching viewer/tool (the request is reused across viewers).
 */
object DocentDiffMarker {
    val THREADS: Key<MutableList<CommentThread>> = Key.create("docent.diff.threads")

    /** Routes a card's reviewer remark to the live Docent (null → comments stay local). */
    val POSTER: Key<CommentPoster> = Key.create("docent.diff.poster")

    /** Whether interactive comment affordances are enabled (an agent is connected). Absent → treated as true. */
    val INTERACTIVE: Key<Boolean> = Key.create("docent.diff.interactive")
}

/**
 * Carries the inline comment cards onto the diff surface — in *both* the side-by-side and unified
 * viewers — and the gutter "+" to add new ones. The diff viewers' editors are real `EditorEx`, so the
 * card embedding is identical to the standalone editor cell; the only twist is the unified viewer maps
 * both sides into one document, so after-side lines are translated via its line convertor.
 *
 * Threads are pinned to the *after* side. We embed only after the viewer's first rediff, so the
 * unified line mapping is valid (the listener is registered in onViewerCreated, before init()).
 */
class DocentDiffCommentExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val threads = request.getUserData(DocentDiffMarker.THREADS) ?: return
        val poster = request.getUserData(DocentDiffMarker.POSTER)
        val interactive = request.getUserData(DocentDiffMarker.INTERACTIVE) ?: true
        // The section's focus spans (1-based after-side lines), when this file is focused on regions. Docent
        // comments outside every span belong to another section, so they're shown collapsed.
        val focus = request.getUserData(DocentFocusMarker.RANGES).orEmpty()
        val binding = bindingFor(viewer) ?: return
        val base = viewer as? DiffViewerBase
        if (base == null) {
            install(binding, threads, poster, focus, interactive)
            return
        }
        var installed = false
        base.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                if (installed) return
                installed = true
                install(binding, threads, poster, focus, interactive)
            }
        })
    }

    /** Maps the "after" file (where comments live) to whichever editor a given viewer exposes. */
    private class Binding(
        val editor: EditorEx,
        /** after-side line (0-based) -> this editor's line (0-based); negative to skip. */
        val afterToEditor: (Int) -> Int,
        /** this editor's line (0-based) -> after-side line (0-based); negative if not an after line. */
        val editorToAfter: (Int) -> Int,
    )

    private fun bindingFor(viewer: FrameDiffTool.DiffViewer): Binding? = when (viewer) {
        is TwosideTextDiffViewer -> Binding(
            editor = viewer.getEditor(Side.RIGHT), // the "after" editor
            afterToEditor = { it },
            editorToAfter = { it },
        )
        is UnifiedDiffViewer -> Binding(
            editor = viewer.editor,
            afterToEditor = { viewer.transferLineToOneside(Side.RIGHT, it) },
            // -1 on a removed (left-only) line: no after line there, so no comment.
            editorToAfter = { viewer.transferLineFromOnesideStrict(Side.RIGHT, it) },
        )
        else -> null
    }

    private fun install(binding: Binding, threads: MutableList<CommentThread>, poster: CommentPoster?, focus: List<IntRange>, interactive: Boolean) {
        val editor = binding.editor
        val document = editor.document
        fun lastLine() = (document.lineCount - 1).coerceAtLeast(0)

        fun embed(thread: CommentThread) {
            // A seeded Docent comment outside every focus span belongs to another section → show it collapsed
            // (inside a span → expanded). Reviewer ("you") cards and in-progress composes are left alone.
            if (focus.isNotEmpty() && thread.author.equals("docent", ignoreCase = true) && !thread.composing) {
                thread.collapsed = focus.none { thread.line in it }
            }
            val editorLine = binding.afterToEditor(thread.line - 1)
            if (editorLine < 0) return
            val offset = document.getLineStartOffset(editorLine.coerceIn(0, lastLine()))
            val card = CommentCard(thread).apply { this.poster = poster; this.interactive = interactive }
            val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
                editor,
                card,
                EditorEmbeddedComponentManager.Properties(
                    EditorEmbeddedComponentManager.ResizePolicy.none(),
                    null,   // rendererFactory
                    true,   // relatesToPrecedingText
                    true,   // showAbove
                    false,  // showWhenFolded
                    true,   // fullWidth
                    0,      // priority
                    offset,
                ),
            )
            card.onChanged = {
                inlay?.update()
                editor.component.revalidate()
                editor.component.repaint()
            }
            card.onRemove = {
                inlay?.let { Disposer.dispose(it) }
                threads.remove(thread)
            }
            editor.component.revalidate()
            editor.component.repaint()
        }

        threads.toList().forEach { embed(it) }

        // The gutter "+" (adding new comments) requires a connected agent — without one, the review is read-only,
        // so skip it entirely. Seeded Docent comments above still render (read-only via the card's interactive flag).
        if (!interactive) return

        // GitHub-style "+" in the gutter, following the caret — but only on lines that exist on the
        // "after" side (so you can't comment on a deleted line in the unified view).
        var plus: RangeHighlighter? = null
        fun showPlusAt(editorLine: Int) {
            plus?.let { editor.markupModel.removeHighlighter(it) }
            plus = null
            val afterLine = binding.editorToAfter(editorLine)
            if (afterLine < 0) return
            val safe = editorLine.coerceIn(0, lastLine())
            plus = editor.markupModel.addLineHighlighter(safe, HighlighterLayer.LAST, null).apply {
                gutterIconRenderer = object : GutterIconRenderer() {
                    override fun getIcon(): Icon = AllIcons.General.Add
                    override fun getTooltipText() = "Add a comment here"
                    override fun isNavigateAction() = true
                    override fun getClickAction(): AnAction = object : AnAction() {
                        override fun actionPerformed(e: AnActionEvent) {
                            val thread = CommentThread("you", "", afterLine + 1).apply { composing = true }
                            threads.add(thread)
                            embed(thread)
                        }
                    }
                    override fun equals(other: Any?) = other === this
                    override fun hashCode() = System.identityHashCode(this)
                }
            }
        }
        showPlusAt(editor.caretModel.logicalPosition.line)
        editor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = showPlusAt(event.newPosition.line)
        })
    }
}
