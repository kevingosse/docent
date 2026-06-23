package com.kevingosse.docent.ui

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import javax.swing.Icon

// A dimmed (out-of-focus) changed hunk is annotated with the section that *does* focus it, looked up in the
// cross-section ownership map ([DocentFocusMarker.OWNERS]). Any hunk no narrating section claims belongs to the
// synthesized "Other changes" catch-all, so an unmatched hunk is labelled (and links to) "Other changes".

/** One section's claim on a file in view (it may focus several disjoint spans) — names a dimmed hunk's owner.
 *  [onNavigate] opens this same file under the owning section, so the dimmed-hunk hint can be a clickable link. */
data class RegionOwner(
    val sectionIndex: Int,
    val headline: String,
    val ranges: List<IntRange>,
    val onNavigate: (() -> Unit)? = null,
)

/**
 * Shows the **whole-file diff** (after = the real working-tree document, so navigation keeps working) and
 * de-emphasises everything outside the current section's focus by fading only the *text foreground* — the
 * diff's added/removed (green/red) backgrounds are left untouched. A section may focus **several disjoint
 * line spans** of one file; the union is lit and everything else dims. Each *dimmed change* (a changed hunk
 * outside every focus span — never folded, so visible) gets a subtle block-inlay row naming the section it
 * belongs to. Works in both side-by-side and unified viewers, including section-scoped F7 / Shift+F7.
 *
 * The overlay + navigation are owned by a per-diff [DimmingController] (so [ToggleDimmingAction] can flip
 * the overlay and [SectionChangeAction] can drive navigation). No-ops unless the request carries
 * [DocentFocusMarker.RANGES].
 */
class DocentDimmingDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val ranges = request.getUserData(DocentFocusMarker.RANGES)?.takeIf { it.isNotEmpty() } ?: return
        val owners = request.getUserData(DocentFocusMarker.OWNERS).orEmpty()
        val otherNav = request.getUserData(DocentFocusMarker.OTHER_CHANGES_NAV)
        val controller = request.getUserData(DocentFocusMarker.CONTROLLER) ?: DimmingController()
        val base = viewer as? DiffViewerBase
        if (base == null) {
            register(viewer, ranges, owners, otherNav, controller)
            return
        }
        var installed = false
        base.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                if (installed) return
                installed = true
                register(viewer, ranges, owners, otherNav, controller)
            }
        })
    }

    /** [ranges] are the focus spans in 1-based "after" (right-side) line numbers, inclusive. [owners] names the
     *  other sections that focus this same file, so a dimmed hunk can point at the section that owns it;
     *  [otherNav] navigates to this file under the "Other changes" section, for hunks no section claims. */
    private fun register(viewer: FrameDiffTool.DiffViewer, ranges: List<IntRange>, owners: List<RegionOwner>, otherNav: (() -> Unit)?, controller: DimmingController) {
        // 0-based, after-side spans. Side-by-side RIGHT == after-side, so these are used directly there.
        val spans0 = ranges.map { (it.first - 1)..(it.last - 1) }
        when (viewer) {
            is SimpleDiffViewer -> {
                controller.addTarget(viewer.getEditor(Side.RIGHT), spans0)
                controller.addTarget(viewer.getEditor(Side.LEFT), spans0)
                controller.setNavigator { forward -> navigateSideBySide(viewer, spans0, forward) }
                controller.setHintInstaller { sideBySideHints(viewer, controller, spans0, owners, otherNav) }
                installSectionNavShortcuts(viewer, controller)
            }
            is TwosideTextDiffViewer -> {
                // Some other two-side text viewer: dim, but no change list to navigate/annotate.
                controller.addTarget(viewer.getEditor(Side.RIGHT), spans0)
                controller.addTarget(viewer.getEditor(Side.LEFT), spans0)
            }
            is UnifiedDiffViewer -> {
                // The unified editor merges both sides; translate each after-side span into its line space.
                val spansU = spans0.map { sp ->
                    val s = runCatching { viewer.transferLineToOneside(Side.RIGHT, sp.first) }.getOrDefault(sp.first)
                    val e = runCatching { viewer.transferLineToOneside(Side.RIGHT, sp.last) }.getOrDefault(sp.last)
                    s..e
                }
                controller.addTarget(viewer.editor, spansU)
                controller.setNavigator { forward -> navigateUnified(viewer, spans0, forward) }
                controller.setHintInstaller { unifiedHints(viewer, controller, spans0, owners, otherNav) }
                installSectionNavShortcuts(viewer, controller)
            }
            else -> {}
        }
    }

    // ----- Hints: a row above each dimmed (out-of-focus) changed hunk -----

    private fun sideBySideHints(viewer: SimpleDiffViewer, controller: DimmingController, spans0: List<IntRange>, owners: List<RegionOwner>, otherNav: (() -> Unit)?) {
        val right = viewer.getEditor(Side.RIGHT)
        if (right.isDisposed) return
        viewer.diffChanges
            .filter { ch -> spans0.none { overlapsRight(ch.getStartLine(Side.RIGHT), ch.getEndLine(Side.RIGHT), it) } }
            .forEach { change ->
                val start = change.getStartLine(Side.RIGHT)
                val owner = ownerFor(start, change.getEndLine(Side.RIGHT), owners)
                controller.addHintInlay(right, start, labelFor(owner), owner?.onNavigate ?: otherNav)
            }
    }

    private fun unifiedHints(viewer: UnifiedDiffViewer, controller: DimmingController, spans0: List<IntRange>, owners: List<RegionOwner>, otherNav: (() -> Unit)?) {
        val editor = viewer.editor
        if (editor.isDisposed) return
        val changes = viewer.diffChanges ?: return
        changes.forEach { change ->
            val afterStart = runCatching { viewer.transferLineFromOneside(Side.RIGHT, change.line1) }.getOrDefault(-1)
            if (afterStart < 0) return@forEach
            if (spans0.none { afterStart in it }) {
                val afterEnd = runCatching { viewer.transferLineFromOneside(Side.RIGHT, change.line2) }.getOrDefault(afterStart + 1)
                val owner = ownerFor(afterStart, maxOf(afterEnd, afterStart + 1), owners)
                controller.addHintInlay(editor, change.line1, labelFor(owner), owner?.onNavigate ?: otherNav)
            }
        }
    }

    /** The section that focuses any part of the after-side change [start0, endExcl0) (0-based; end exclusive),
     *  if any. Matches the dimming by using **overlap**, not just the start line — so a change that begins
     *  between two focus ranges but reaches into one is attributed to its owner, not mislabelled "Other changes". */
    private fun ownerFor(start0: Int, endExcl0: Int, owners: List<RegionOwner>): RegionOwner? =
        owners.firstOrNull { o -> o.ranges.any { overlapsRight(start0, endExcl0, (it.first - 1)..(it.last - 1)) } }

    /** Label for a dimmed hunk owned by [owner]. No owner, or the synthesized catch-all, both read "Other
     *  changes" (a hunk no narrating section claims belongs there, and that reads better than "Section N · …"). */
    private fun labelFor(owner: RegionOwner?): String =
        if (owner == null || owner.headline == DocentReviewController.OTHER_CHANGES_HEADLINE) {
            DocentReviewController.OTHER_CHANGES_HEADLINE
        } else {
            "Section ${owner.sectionIndex + 1} · ${owner.headline}"
        }

    // ----- Section-scoped change navigation (both viewers) — steps through changes in any focus span -----

    private fun navigateSideBySide(viewer: SimpleDiffViewer, spans0: List<IntRange>, forward: Boolean) {
        val right = viewer.getEditor(Side.RIGHT)
        if (right.isDisposed) return
        val changes = viewer.diffChanges
            .filter { ch -> spans0.any { overlapsRight(ch.getStartLine(Side.RIGHT), ch.getEndLine(Side.RIGHT), it) } }
            .sortedBy { it.getStartLine(Side.RIGHT) }
        if (changes.isEmpty()) return
        val caret = right.caretModel.logicalPosition.line
        val target = if (forward) changes.firstOrNull { it.getStartLine(Side.RIGHT) > caret } ?: changes.first()
        else changes.lastOrNull { it.getStartLine(Side.RIGHT) < caret } ?: changes.last()
        moveCaret(viewer.getEditor(Side.LEFT), target.getStartLine(Side.LEFT))
        moveCaret(right, target.getStartLine(Side.RIGHT))
        right.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    private fun navigateUnified(viewer: UnifiedDiffViewer, spans0: List<IntRange>, forward: Boolean) {
        val editor = viewer.editor
        if (editor.isDisposed) return
        val changes = viewer.diffChanges ?: return
        val inFocus = changes
            .filter {
                val after = runCatching { viewer.transferLineFromOneside(Side.RIGHT, it.line1) }.getOrDefault(-1)
                after >= 0 && spans0.any { sp -> after in sp }
            }
            .sortedBy { it.line1 }
        if (inFocus.isEmpty()) return
        val caret = editor.caretModel.logicalPosition.line
        val target = if (forward) inFocus.firstOrNull { it.line1 > caret } ?: inFocus.first()
        else inFocus.lastOrNull { it.line1 < caret } ?: inFocus.last()
        moveCaret(editor, target.line1)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
    }

    /** A change (after-side [start0, end0), end exclusive) overlaps a focus [span] (0-based, inclusive). */
    private fun overlapsRight(start0: Int, end0: Int, span: IntRange): Boolean = end0 > span.first && start0 <= span.last

    /** Rebind F7 / Shift+F7 inside this diff so they step only through the current section's changes. */
    private fun installSectionNavShortcuts(viewer: DiffViewerBase, controller: DimmingController) {
        val am = ActionManager.getInstance()
        val comp = viewer.component
        am.getAction(IdeActions.ACTION_NEXT_DIFF)?.shortcutSet?.let {
            SectionChangeAction(controller, forward = true, "Next Change in Section", AllIcons.Actions.NextOccurence)
                .registerCustomShortcutSet(it, comp, viewer)
        }
        am.getAction(IdeActions.ACTION_PREVIOUS_DIFF)?.shortcutSet?.let {
            SectionChangeAction(controller, forward = false, "Previous Change in Section", AllIcons.Actions.PreviousOccurence)
                .registerCustomShortcutSet(it, comp, viewer)
        }
    }
}

/** Owns the dimming overlay (fades + per-dimmed-change hints) + the section-scoped navigator for one diff. */
class DimmingController {

    /** [spans0] are this editor's focused line spans (0-based, inclusive); everything else is dimmed. */
    private data class Target(val editor: EditorEx, val spans0: List<IntRange>)

    /** A dimmed-hunk hint that links to its owning section. */
    private data class ClickableHint(val inlay: Inlay<*>, val renderer: HintRenderer, val onClick: () -> Unit)

    private val targets = mutableListOf<Target>()
    private val highlighters = mutableListOf<RangeHighlighter>()
    private val inlays = mutableListOf<Inlay<*>>()
    private val clickableHints = mutableListOf<ClickableHint>()
    /** Editors already wired with our click/hover listener (kept across toggle so we don't double-register). */
    private val mouseEditors = mutableSetOf<EditorEx>()
    /** The hint the mouse is currently over (rendered as a link); only one at a time. */
    private var hovered: ClickableHint? = null
    private var hintInstaller: (() -> Unit)? = null
    private var navigator: ((Boolean) -> Unit)? = null

    var enabled = true
        private set

    fun addTarget(editor: EditorEx, spans0: List<IntRange>) {
        val target = Target(editor, spans0)
        targets += target
        if (enabled) applyFades(target)
    }

    fun setHintInstaller(installer: () -> Unit) {
        hintInstaller = installer
        if (enabled) installer()
    }

    /** The active viewer supplies how to step prev/next within the focus spans. */
    fun setNavigator(nav: (Boolean) -> Unit) {
        navigator = nav
    }

    fun navigateChange(forward: Boolean) = navigator?.invoke(forward) ?: Unit

    fun setEnabled(value: Boolean) {
        if (value == enabled) return
        enabled = value
        if (value) {
            targets.forEach { applyFades(it) }
            hintInstaller?.invoke()
        } else {
            clearArtifacts()
        }
    }

    /** Add a block-inlay hint row above [line] (a changed line, so never folded → actually shows). It renders
     *  in the muted style by default; when [onClick] is supplied it becomes a link on hover and clicking it runs
     *  the action (navigate to the owning section). */
    fun addHintInlay(editor: EditorEx, line: Int, text: String, onClick: (() -> Unit)? = null) {
        if (editor.isDisposed) return
        val color = ColorUtil.mix(editor.colorsScheme.defaultForeground, editor.colorsScheme.defaultBackground, 0.38)
        val lastLine = (editor.document.lineCount - 1).coerceAtLeast(0)
        val offset = editor.document.getLineStartOffset(line.coerceIn(0, lastLine))
        val renderer = HintRenderer("↳ $text", color)
        val inlay = editor.inlayModel.addBlockElement(offset, false, true, 0, renderer)
        if (inlay != null) {
            inlays += inlay
            if (onClick != null) {
                clickableHints += ClickableHint(inlay, renderer, onClick)
                installClickHandler(editor)
            }
        }
    }

    /** Make the link hints in [editor] respond to clicks (navigate), underline on hover, and show a hand cursor.
     *  Installed once per editor; the listeners consult the live [clickableHints] (cleared on toggle-off, rebuilt). */
    private fun installClickHandler(editor: EditorEx) {
        if (!mouseEditors.add(editor)) return
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(e: EditorMouseEvent) {
                val hit = hintAt(e.mouseEvent.point) ?: return
                e.consume()
                hit.onClick()
            }

            override fun mouseExited(e: EditorMouseEvent) = setHovered(null)
        })
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                val hit = hintAt(e.mouseEvent.point)
                setHovered(hit)
                editor.setCustomCursor(this@DimmingController, if (hit != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else null)
            }
        })
    }

    private fun hintAt(point: Point): ClickableHint? =
        clickableHints.firstOrNull { it.inlay.bounds?.contains(point) == true }

    /** Flip which hint renders as a link, repainting only the affected row(s). */
    private fun setHovered(hit: ClickableHint?) {
        if (hit === hovered) return
        hovered?.let { it.renderer.hovered = false; runCatching { it.inlay.update() } }
        hovered = hit
        hit?.let { it.renderer.hovered = true; runCatching { it.inlay.update() } }
    }

    private fun clearArtifacts() {
        highlighters.forEach { runCatching { it.dispose() } }
        highlighters.clear()
        inlays.forEach { runCatching { Disposer.dispose(it) } }
        inlays.clear()
        clickableHints.clear()
        hovered = null
    }

    /** Fade the text foreground in the *gaps* between focus spans (leaving diff backgrounds intact). */
    private fun applyFades(t: Target) {
        val editor = t.editor
        if (editor.isDisposed) return
        val doc = editor.document
        val lastLine = (doc.lineCount - 1).coerceAtLeast(0)
        val spans = mergeSpans(t.spans0, lastLine)
        val muted = ColorUtil.mix(editor.colorsScheme.defaultForeground, editor.colorsScheme.defaultBackground, 0.62)
        val dim = TextAttributes().apply { foregroundColor = muted }
        var cursor = 0
        for (sp in spans) {
            if (sp.first > cursor) addDim(editor, doc, cursor, sp.first - 1, dim)
            cursor = sp.last + 1
        }
        if (cursor <= lastLine) addDim(editor, doc, cursor, lastLine, dim)
    }

    private fun addDim(editor: EditorEx, doc: com.intellij.openapi.editor.Document, fromLine: Int, toLine: Int, attrs: TextAttributes) {
        if (fromLine > toLine) return
        highlighters += editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(fromLine), doc.getLineEndOffset(toLine),
            HighlighterLayer.LAST, attrs, HighlighterTargetArea.EXACT_RANGE,
        )
    }

    /** Clamp to the document, drop empties, sort, and merge overlapping/adjacent spans into a tidy cover. */
    private fun mergeSpans(spans0: List<IntRange>, lastLine: Int): List<IntRange> {
        val clamped = spans0
            .map { it.first.coerceIn(0, lastLine)..it.last.coerceIn(0, lastLine) }
            .filter { it.first <= it.last }
            .sortedBy { it.first }
        val out = mutableListOf<IntRange>()
        for (sp in clamped) {
            val last = out.lastOrNull()
            if (last != null && sp.first <= last.last + 1) {
                out[out.size - 1] = last.first..maxOf(last.last, sp.last)
            } else {
                out += sp
            }
        }
        return out
    }
}

/** Toolbar toggle for the dimming overlay. */
class ToggleDimmingAction(private val controller: DimmingController) : ToggleAction(
    "Dim Other Regions",
    "Show or hide the dimming of code outside the focus range",
    AllIcons.General.InspectionsEye,
) {
    override fun isSelected(e: AnActionEvent): Boolean = controller.enabled
    override fun setSelected(e: AnActionEvent, state: Boolean) = controller.setEnabled(state)
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** Next/previous change, scoped to the current section's focus spans (toolbar + F7/Shift+F7). */
class SectionChangeAction(
    private val controller: DimmingController,
    private val forward: Boolean,
    text: String,
    icon: Icon,
) : DumbAwareAction(text, null, icon) {
    override fun actionPerformed(e: AnActionEvent) = controller.navigateChange(forward)
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/** Renders a muted, italic block-inlay hint row inside the editor. By default it's the quiet gray style; while
 *  [hovered] (only ever set for clickable hints) it reads as a link — link color + underline — so the reviewer
 *  sees it's a jump to the section that owns the dimmed hunk. */
private class HintRenderer(private val text: String, private val color: Color) : EditorCustomElementRenderer {
    var hovered = false

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        inlay.editor.contentComponent.getFontMetrics(font(inlay.editor)).stringWidth(text) + JBUI.scale(12)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = inlay.editor.lineHeight

    override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
        g.color = if (hovered) JBUI.CurrentTheme.Link.Foreground.ENABLED else color
        g.font = font(inlay.editor)
        val fm = g.fontMetrics
        val x = targetRegion.x + JBUI.scale(6)
        val baseline = targetRegion.y + (targetRegion.height - fm.height) / 2 + fm.ascent
        g.drawString(text, x, baseline)
        if (hovered) g.drawLine(x, baseline + JBUI.scale(1), x + fm.stringWidth(text), baseline + JBUI.scale(1))
    }

    private fun font(editor: Editor): Font = editor.colorsScheme.getFont(EditorFontType.ITALIC)
}

private fun moveCaret(editor: Editor, line: Int) {
    if (editor.isDisposed) return
    val safe = line.coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
    editor.caretModel.moveToLogicalPosition(LogicalPosition(safe, 0))
}

/** Marker scoping [DocentDimmingDiffExtension] to focus-region diffs and carrying the focus + overlay owner. */
object DocentFocusMarker {
    /** Focus spans in 1-based "after"-side line numbers, inclusive (a file may be split into several). */
    val RANGES: Key<List<IntRange>> = Key.create("docent.diff.focusRanges")
    val CONTROLLER: Key<DimmingController> = Key.create("docent.diff.dimmingController")
    /** Other sections' focus regions on the same file (a file may be split across sections), used to name dimmed hunks. */
    val OWNERS: Key<List<RegionOwner>> = Key.create("docent.diff.regionOwners")
    /** Navigate to this file under the synthesized "Other changes" section — the link target for a dimmed hunk
     *  that no narrating section claims. */
    val OTHER_CHANGES_NAV: Key<() -> Unit> = Key.create("docent.diff.otherChangesNav")
}
