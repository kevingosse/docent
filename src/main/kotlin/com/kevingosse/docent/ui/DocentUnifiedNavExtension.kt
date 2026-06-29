package com.kevingosse.docent.ui

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.fileEditor.impl.IdeDocumentHistoryImpl
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.util.PsiNavigateUtil
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.awt.event.MouseEvent

/**
 * Restores Ctrl-click / Ctrl-B **go-to-declaration inside the unified diff viewer**.
 *
 * The side-by-side viewer's "after" editor wraps the real working-tree document, so navigation already works
 * there (see [DocentPanel]). The unified viewer instead renders one *synthetic* merged document interleaving
 * both sides, with no PSI behind it — so the platform's goto-declaration finds nothing under the caret. This
 * is a platform limitation: the stock IDE unified diff behaves the same way.
 *
 * Both paths first map the unified caret/click position back to the **after** side via
 * [UnifiedDiffViewer.transferLineFromOnesideStrict]. Only the after side is backed by a real file — the
 * "before" side is synthetic git-show text — so removed (before-only) lines stay non-navigable (an accepted
 * limitation; you rarely jump *from* deleted code). From there resolution is a hybrid:
 *
 *  1. **PSI path (IntelliJ / Java / Kotlin).** Run the standard goto pipeline
 *     ([GotoDeclarationAction.findTargetElement]) against the after file, handing it the viewer's own per-side
 *     **imaginary** highlight editor ([UnifiedDiffViewer.getHighlightEditors]) — an `ImaginaryEditor` wrapping
 *     the real after-side document and reporting the real `VirtualFile`, which is what the platform itself uses
 *     to run highlighting/inspections per side. When this resolves we navigate in place, without opening any
 *     intermediate editor.
 *  2. **Backend path (Rider / C#).** Rider has no IntelliJ PSI for C# — resolution lives in the ReSharper
 *     backend, which only acts on the focused frontend text control. So when (1) finds nothing we open the real
 *     after file at the offset (a real, backend-registered editor) and delegate to `ACTION_GOTO_DECLARATION`,
 *     which in Rider is the backend-driven goto. That two-hop leaves a transient back-stop, which
 *     [dropIntermediatePlace] removes so a single Back returns to the diff.
 *
 * Registered globally as a `diff.DiffExtension`; it no-ops on every viewer except [UnifiedDiffViewer].
 */
class DocentUnifiedNavExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val unified = viewer as? UnifiedDiffViewer ?: return // side-by-side already navigates on the after editor
        val project = context.project ?: return
        // Install after the first rediff: getHighlightEditors() and the line convertor are only valid once the
        // unified document has been built (mirrors DocentDiffCommentExtension / DocentDimmingDiffExtension).
        var installed = false
        unified.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                if (installed) return
                installed = true
                install(unified, project)
            }
        })
    }

    private fun install(viewer: UnifiedDiffViewer, project: Project) {
        val editor = viewer.editor
        if (editor.isDisposed) return

        // Ctrl-click (Cmd-click on macOS) with the left button → resolve at the clicked position.
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val mouse = event.mouseEvent
                val withModifier = if (SystemInfo.isMac) mouse.isMetaDown else mouse.isControlDown
                if (!withModifier || mouse.button != MouseEvent.BUTTON1) return
                if (navigateAt(viewer, project, editor.xyToLogicalPosition(mouse.point))) event.consume()
            }
        })

        // Ctrl-B (and any other ACTION_GOTO_DECLARATION shortcut) → resolve at the caret. Scoped to this editor's
        // component so it doesn't shadow the global action elsewhere; the platform's own no-ops here anyway.
        ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION)?.shortcutSet?.let { shortcuts ->
            object : DumbAwareAction() {
                override fun actionPerformed(e: AnActionEvent) {
                    navigateAt(viewer, project, editor.caretModel.logicalPosition)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            }.registerCustomShortcutSet(shortcuts, editor.contentComponent, viewer)
        }
    }

    /**
     * Map [pos] (a logical position in the unified document) to the after side and run goto-declaration there.
     * Returns whether it resolved, so the mouse handler can consume the click only when we actually navigated.
     */
    private fun navigateAt(viewer: UnifiedDiffViewer, project: Project, pos: LogicalPosition): Boolean =
        runCatching {
            // <0 ⇒ a removed (before-only) line: no after-side counterpart, so nothing to navigate to.
            val afterLine = viewer.transferLineFromOnesideStrict(Side.RIGHT, pos.line)
            if (afterLine < 0) return false

            val afterDoc = viewer.getDocument(Side.RIGHT)
            val lastLine = (afterDoc.lineCount - 1).coerceAtLeast(0)
            val line = afterLine.coerceIn(0, lastLine)
            // The after-side line text is identical to the unified row (only relocated), so the column carries over.
            val lineStart = afterDoc.getLineStartOffset(line)
            val offset = (lineStart + pos.column).coerceIn(lineStart, afterDoc.getLineEndOffset(line))

            // (1) PSI path — IntelliJ / Java / Kotlin. The viewer's after-side imaginary editor wraps the real
            // after document and reports the real VirtualFile, so the standard goto pipeline resolves directly and
            // we navigate without opening the source file. Returns null where there's no IntelliJ PSI (Rider C#).
            val imaginary = viewer.highlightEditors.firstOrNull { it.document === afterDoc }
            if (imaginary != null) {
                val target = ApplicationManager.getApplication().runReadAction<PsiElement?> {
                    GotoDeclarationAction.findTargetElement(project, imaginary, offset)
                }
                if (target != null) {
                    navigateTo(target)
                    return true
                }
            }

            // (2) Backend path — Rider / C#. No IntelliJ PSI to resolve against; the backend only acts on the
            // focused frontend text control. So open the real after-side file at the offset (a real, backend-
            // registered editor) and delegate to ACTION_GOTO_DECLARATION — in Rider that's the backend-driven
            // goto. Guard to an identifier char so a click on whitespace/punctuation doesn't open the file.
            if (!isIdentifierOffset(afterDoc.charsSequence, offset)) return false
            val vfile = FileDocumentManager.getInstance().getFile(afterDoc) ?: return false
            openAndGotoDeclaration(project, vfile, offset)
            true
        }.getOrDefault(false)

    private fun navigateTo(target: PsiElement) {
        val navigatable = target as? Navigatable
        if (navigatable != null && navigatable.canNavigate()) navigatable.navigate(true)
        else PsiNavigateUtil.navigate(target)
    }

    /**
     * Open [vfile] at [offset] in a real editor (focused, so the Rider backend binds it as the active text
     * control) and fire the platform goto-declaration on it. The fire is deferred to a later EDT cycle because
     * the frontend↔backend text-control sync (which carries the caret the backend resolves against) is async —
     * firing synchronously can beat the sync and resolve nothing.
     */
    private fun openAndGotoDeclaration(project: Project, vfile: VirtualFile, offset: Int) {
        val history = IdeDocumentHistory.getInstance(project)
        // Snapshot the back-stack so we can later drop *only* the transient "after-file@click" stop that the
        // two-hop navigation (open file → backend goto) leaves behind, keeping the diff as the Back target.
        val backBefore = history.backPlaces.toList()

        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, vfile, offset), true) ?: return
        editor.caretModel.moveToOffset(offset)
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            val action = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION) ?: return@invokeLater
            ActionUtil.invokeAction(action, editor.contentComponent, ActionPlaces.UNKNOWN, null, null)
            // The backend navigates asynchronously and only *then* pushes the intermediate place, so the cleanup
            // is deferred past a short settle. If the timing is off we simply find nothing and leave history as-is.
            AppExecutorUtil.getAppScheduledExecutorService().schedule({
                ApplicationManager.getApplication().invokeLater({ dropIntermediatePlace(history, backBefore, vfile) }, ModalityState.any())
            }, 400, TimeUnit.MILLISECONDS)
        }
    }

    /** Drop the transient after-file back-stop the two-hop backend navigation introduces, so one Back returns to
     *  the diff. Removes only entries that are (a) newly added since [backBefore] and (b) on the opened [vfile];
     *  the diff's own back entry (a different, light virtual file) is preserved. */
    private fun dropIntermediatePlace(history: IdeDocumentHistory, backBefore: List<IdeDocumentHistoryImpl.PlaceInfo>, vfile: VirtualFile) {
        runCatching {
            history.backPlaces
                .filter { it !in backBefore && it.file == vfile }
                .forEach { history.removeBackPlace(it) }
        }
    }

    /** Whether [offset] sits on (or just past) an identifier character — a cheap gate so the backend fallback
     *  only opens the file when the click is plausibly on a symbol, not on whitespace or punctuation. */
    private fun isIdentifierOffset(text: CharSequence, offset: Int): Boolean {
        fun ident(i: Int) = i in text.indices && (text[i].isLetterOrDigit() || text[i] == '_')
        return ident(offset) || ident(offset - 1)
    }
}
