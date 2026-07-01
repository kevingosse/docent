package com.kevingosse.docent.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.OpenDocentReviewAction
import com.kevingosse.docent.trail.Anchor
import com.kevingosse.docent.trail.GitChangeSet
import com.kevingosse.docent.trail.Trail
import com.kevingosse.docent.trail.LineRange
import com.kevingosse.docent.trail.TrailLoader
import com.kevingosse.docent.trail.Section
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Coordinates the split review UI (the navigation tool window on the left, the review tab in the editor):
 * it owns the loaded [Trail] and the current selection, and notifies both views. The nav panel writes
 * selection (clicking a section/file); the review panel reads it and renders. Clicking a section opens/focuses the
 * single review tab — see [selectSection].
 *
 * Everything here is touched on the EDT (Swing handlers, or `invokeLater` from the MCP handoff).
 */
@Service(Service.Level.PROJECT)
class DocentReviewController(private val project: Project) {

    interface Listener {
        /** The trail was (re)loaded; rebuild from scratch. */
        fun onModelChanged() {}
        /** The selected section/file changed; refresh the view + highlights. */
        fun onSelectionChanged() {}
        /** The agent-connection state flipped; re-evaluate interactive affordances (conversation, "+"). */
        fun onConnectionChanged() {}
    }

    init {
        // Bridge the service's connection-changed signal to our listeners, on the EDT. Lets open views enable
        // or disable their interactive affordances the moment an agent is connected/disconnected.
        DocentReviewService.getInstance(project).onConnectionChanged = {
            ApplicationManager.getApplication().invokeLater({ listeners.forEach { it.onConnectionChanged() } }, ModalityState.any())
        }
    }

    var trail: Trail? = null
        private set
    var loadError: String? = null
        private set

    /** -1 = overview (the thesis); otherwise the section index. */
    var currentSectionIndex = -1
        private set
    var currentFileIndex = 0
        private set
    val visited = mutableSetOf<Int>()

    /** Whether the review tab is actually open in the editor. The nav highlights a row only when it is, so a
     *  closed middle pane shows no selection — and re-clicking the same item reopens it. Driven by the review
     *  panel's lifecycle ([onReviewTabOpened]/[onReviewTabClosed]). */
    var reviewTabOpen = false
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    /** Load (or reload) the trail from the active path. Resets selection; does NOT touch the agent loop. */
    fun loadTrail() {
        trail = try {
            loadError = null
            val path = DocentReviewService.getInstance(project).trailPath
            if (path.isNullOrBlank()) null else mergeSamePathAnchors(TrailLoader.load(path))
        } catch (t: Throwable) {
            loadError = t.message ?: t.toString()
            null
        }
        currentSectionIndex = -1
        currentFileIndex = 0
        visited.clear()
        listeners.forEach { it.onModelChanged() }
        appendOtherChangesSection()
    }

    /**
     * "Present 100%, hide nothing" (DESIGN §1): every changed line must be in view, even the ones the author
     * didn't narrate. We append a final synthesized **"Other changes"** section that collects, so nothing is
     * silently dropped:
     *  - **wholly-unnarrated files**, as whole-file diffs; and
     *  - **the leftover hunks of narrated files** — changed lines outside every section's focus span. In their
     *    own section those lines dim ("not covered by a section"); here they're focused (un-grayed) so the
     *    reviewer still sees them. (And because this section now claims them, the dimmed hint in the narrating
     *    section reads "Other changes" instead of "not covered by a section".)
     * Runs off the EDT (git calls); re-fires [Listener.onModelChanged] when ready.
     */
    private fun appendOtherChangesSection() {
        val base = project.basePath ?: return
        val loaded = trail ?: return
        val beforeRef = loaded.beforeRef() ?: return // no diff base (no baseRef and no commit) → nothing to enumerate
        ApplicationManager.getApplication().executeOnPooledThread {
            // What the narration already covers, per file: the union of focus spans across every section that
            // anchors it. A whole-file anchor (no ranges) marks the file fully covered → nothing left over.
            val coveredByPath = LinkedHashMap<String, MutableList<IntRange>>()
            val fullyCovered = mutableSetOf<String>()
            loaded.sections.flatMap { it.anchors }.forEach { a ->
                val rs = a.focusRanges()
                if (rs.isEmpty()) fullyCovered += a.path else coveredByPath.getOrPut(a.path) { mutableListOf() }.addAll(rs)
            }
            val narrated = coveredByPath.keys + fullyCovered

            // Wholly-unnarrated files → whole-file anchors (un-grayed; no focus spans).
            val wholeFileAnchors = GitChangeSet.forTrail(base, loaded)
                .filter { it.path !in narrated }
                .map { (status, path) ->
                    Anchor(path = path, label = "${path.substringAfterLast('/')} (${GitChangeSet.statusWord(status)})")
                }

            // Narrated-but-partially-focused files → an anchor focusing exactly the *uncovered* changed hunks,
            // so those lines (grayed in their section) show un-grayed here. Skip files with no leftover.
            val leftoverAnchors = coveredByPath.entries
                .filter { it.key !in fullyCovered }
                .mapNotNull { (path, covered) ->
                    val hunks = GitChangeSet.changedLineRanges(base, beforeRef, path)
                    if (hunks.isEmpty()) return@mapNotNull null
                    val uncovered = subtractRanges(hunks, covered)
                    if (uncovered.isEmpty()) return@mapNotNull null
                    Anchor(
                        path = path,
                        ranges = uncovered.map { LineRange(it.first, it.last) },
                        label = "${path.substringAfterLast('/')} (lines not in a section)",
                    )
                }

            val anchors = wholeFileAnchors + leftoverAnchors
            if (anchors.isEmpty()) return@executeOnPooledThread
            val section = Section(
                headline = OTHER_CHANGES_HEADLINE,
                narration = "<p>These files — and regions of narrated files — also changed but aren't part of the " +
                    "guided walkthrough above: supporting edits, mechanical refactors, build/config, docs, and any " +
                    "lines a section didn't focus. They're collected here so the review still shows <i>everything</i>: " +
                    "pick any file on the left to see its full diff (where a file was only partly narrated, the lines " +
                    "not covered by a section are the ones lit here).</p>",
                anchors = anchors,
            )
            ApplicationManager.getApplication().invokeLater({
                // Append only if the same trail is still loaded (guard against a reload racing in).
                if (trail === loaded) {
                    trail = loaded.copy(sections = loaded.sections + section)
                    listeners.forEach { it.onModelChanged() }
                }
            }, ModalityState.any())
        }
    }

    /** Subtract [remove] spans from each of [base]'s spans (interval difference); returns the remaining pieces. */
    private fun subtractRanges(base: List<IntRange>, remove: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (span in base) {
            var pieces = listOf(span)
            for (r in remove) pieces = pieces.flatMap { splitOut(it, r) }
            out += pieces
        }
        return out.filter { it.first <= it.last }
    }

    /** [seg] minus [r]: the part(s) of [seg] not overlapping [r] (0, 1, or 2 pieces). */
    private fun splitOut(seg: IntRange, r: IntRange): List<IntRange> {
        if (r.last < seg.first || r.first > seg.last) return listOf(seg) // disjoint
        val pieces = mutableListOf<IntRange>()
        if (r.first > seg.first) pieces += seg.first..(r.first - 1)
        if (r.last < seg.last) pieces += (r.last + 1)..seg.last
        return pieces
    }

    /**
     * Merge anchors pointing at the same file *within a section* into one file step. The authoring agent
     * sometimes emits two anchors for one file (e.g. two regions of it), which would otherwise show as two
     * confusing duplicate steps in the nav. Union the focus ranges (a whole-file anchor wins — it drops the
     * ranges), concatenate comments, keep the first non-null label.
     */
    private fun mergeSamePathAnchors(j: Trail): Trail =
        j.copy(sections = j.sections.map { s -> s.copy(anchors = mergeAnchors(s.anchors)) })

    private fun mergeAnchors(anchors: List<Anchor>): List<Anchor> {
        if (anchors.size < 2) return anchors
        val byPath = LinkedHashMap<String, MutableList<Anchor>>()
        anchors.forEach { byPath.getOrPut(it.path) { mutableListOf() }.add(it) }
        return byPath.values.map { group ->
            if (group.size == 1) return@map group[0]
            val anyWholeFile = group.any { it.focusRanges().isEmpty() }
            Anchor(
                path = group[0].path,
                ranges = if (anyWholeFile) null else group.flatMap { it.focusRanges() }.map { LineRange(it.first, it.last) },
                label = group.firstNotNullOfOrNull { it.label },
                comments = group.flatMap { it.comments.orEmpty() }.ifEmpty { null },
            )
        }
    }

    /** Load the trail only if nothing is loaded yet. */
    fun ensureLoaded() {
        if (trail == null && loadError == null) loadTrail()
    }

    /** "Reload trail" (dev affordance): tear down any in-flight agent loop, then reload content. */
    fun reloadFromDisk() {
        DocentReviewService.getInstance(project).reset()
        loadTrail()
    }

    /** "Load trail…": point the review at a trail JSON the user picked, then (re)load it from scratch. */
    fun loadTrailFrom(path: String) {
        DocentReviewService.getInstance(project).reset()
        DocentReviewService.getInstance(project).trailPath = path
        loadTrail()
    }

    fun showOverview() = goTo(-1, 0)

    /** The review panel calls these on its own open/close, so the nav highlight tracks the real tab state. */
    fun onReviewTabOpened() {
        reviewTabOpen = true
        listeners.forEach { it.onSelectionChanged() }
    }

    fun onReviewTabClosed() {
        reviewTabOpen = false
        listeners.forEach { it.onSelectionChanged() }
    }

    /**
     * Select a section: show its summary (narration + discuss), NOT a file diff (currentFileIndex = -1 — the
     * file diffs are their own steps). Opens/focuses the review tab; the nav highlights it via the listener.
     */
    fun selectSection(index: Int) = goTo(index, -1)

    fun selectFile(sectionIndex: Int, fileIndex: Int) = goTo(sectionIndex, fileIndex)

    /**
     * Open [path]'s diff under the synthesized "Other changes" section — the link target for a dimmed hunk that no
     * narrating section claims. The section normally already lists the file ([appendOtherChangesSection] surfaces
     * the leftover hunks of narrated files); if it doesn't — e.g. a git-vs-diff-viewer skew left the hunk
     * uncollected — append a whole-file entry on the fly so the change is always viewable un-grayed ("present
     * 100%, hide nothing"), rather than dead-ending on the section summary. No-op if there's no such section.
     */
    fun selectInOtherChanges(path: String) {
        val j = trail ?: return
        val sectionIndex = j.sections.indexOfLast { it.headline == OTHER_CHANGES_HEADLINE }
        if (sectionIndex < 0) return
        val existing = j.sections[sectionIndex].anchors.indexOfFirst { it.path == path }
        if (existing >= 0) {
            selectFile(sectionIndex, existing)
            return
        }
        val section = j.sections[sectionIndex]
        val added = section.copy(
            anchors = section.anchors + Anchor(path = path, label = "${path.substringAfterLast('/')} (not in a section)"),
        )
        trail = j.copy(sections = j.sections.toMutableList().also { it[sectionIndex] = added })
        listeners.forEach { it.onModelChanged() } // rebuild nav so the new file chip shows
        selectFile(sectionIndex, added.anchors.size - 1)
    }

    /**
     * Apply a selection restored from the IDE's global Back/Forward history — updates the view WITHOUT
     * recording a new place. The platform is mid-restore ([DocentFileEditor.setState]); re-recording here would
     * corrupt its back/forward stacks.
     */
    fun restoreSelection(sectionIndex: Int, fileIndex: Int) = applySelection(sectionIndex, fileIndex)

    /**
     * Move the selection and record the *pre-move* place in the IDE's global Back/Forward history, so the
     * toolbar arrows (and Ctrl+Alt+←/→) step through the review the same as ordinary editor navigation.
     *
     * Wrapping the move in a command lets [IdeDocumentHistory] snapshot the current place at command *start*
     * (via [DocentFileEditor.getState]) and push it on finish; the two flags mark the command as a navigation
     * move so it actually lands in the back stack. A no-op selection just re-focuses (nothing to record).
     */
    private fun goTo(sectionIndex: Int, fileIndex: Int) {
        if (sectionIndex == currentSectionIndex && fileIndex == currentFileIndex) {
            applySelection(sectionIndex, fileIndex)
            return
        }
        val history = IdeDocumentHistory.getInstance(project)
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                history.setCurrentCommandHasMoves()
                history.includeCurrentCommandAsNavigation()
                applySelection(sectionIndex, fileIndex)
            },
            "Docent Navigation",
            null,
        )
    }

    private fun applySelection(sectionIndex: Int, fileIndex: Int) {
        currentSectionIndex = sectionIndex
        currentFileIndex = fileIndex
        visited.add(sectionIndex)
        openReviewTab()
        listeners.forEach { it.onSelectionChanged() }
    }

    // ----- Linear step navigation (Prev/Next) -----
    //
    // The steps run, per section, [summary, file 0, file 1, …]: Next walks to the next file in the section and only
    // advances to the next section past the last file (Prev is the reverse). The overview/thesis is NOT part of
    // this sequence (it's reached via the Overview button).

    fun hasNext(): Boolean = stepAfter(currentSectionIndex, currentFileIndex) != null
    fun hasPrev(): Boolean = stepBefore(currentSectionIndex, currentFileIndex) != null

    fun goNext() = stepAfter(currentSectionIndex, currentFileIndex)?.let { goTo(it.first, it.second) } ?: Unit
    fun goPrev() = stepBefore(currentSectionIndex, currentFileIndex)?.let { goTo(it.first, it.second) } ?: Unit

    /** The step after (section, file), or null at the end. fileIndex -1 = the section summary. */
    private fun stepAfter(sectionIndex: Int, fileIndex: Int): Pair<Int, Int>? {
        val sections = trail?.sections ?: return null
        if (sectionIndex < 0) return if (sections.isNotEmpty()) 0 to -1 else null
        val fileCount = sections.getOrNull(sectionIndex)?.anchors?.size ?: 0
        if (fileIndex + 1 < fileCount) return sectionIndex to fileIndex + 1 // next file in this section
        return if (sectionIndex + 1 < sections.size) sectionIndex + 1 to -1 else null // else next section's summary
    }

    /** The step before (section, file), or null at the start. */
    private fun stepBefore(sectionIndex: Int, fileIndex: Int): Pair<Int, Int>? {
        val sections = trail?.sections ?: return null
        if (sectionIndex < 0) return null
        if (fileIndex >= 0) return sectionIndex to fileIndex - 1 // previous file, or the summary (-1) from file 0
        if (sectionIndex - 1 < 0) return null // already at the very first section's summary
        val prev = sectionIndex - 1
        val prevFiles = sections.getOrNull(prev)?.anchors?.size ?: 0
        return prev to prevFiles - 1 // previous section's last file, or its summary (-1) if it has none
    }

    /** Open/focus the single review tab in the editor (middle) pane. */
    private fun openReviewTab() {
        FileEditorManager.getInstance(project)
            .openFile(OpenDocentReviewAction.getOrCreateFile(project), true)
    }

    /**
     * Entry point for "open the review" (Tools action or the MCP handoff): show the nav tool window, open the
     * review tab on the overview. Must run on the EDT.
     */
    fun openReview() {
        ensureLoaded()
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
        openReviewTab()
        listeners.forEach { it.onSelectionChanged() }
    }

    /**
     * Close the Docent surfaces once the review is done: the editor review tab (its dispose fires
     * [onReviewTabClosed], so the nav highlight clears itself) and the nav tool window. Must run on the EDT.
     */
    fun closeReview() {
        FileEditorManager.getInstance(project).closeFile(OpenDocentReviewAction.getOrCreateFile(project))
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.hide()
    }

    /**
     * End the current review and return the UI to its initial (no-trail) state: drop the loaded trail and close
     * the now-stale editor review tab, but leave the nav tool window open on the start-review surface (so the
     * reviewer sees the review wrapped up and where the next one will appear). Must run on the EDT.
     *
     * Deliberately does NOT touch the agent loop — [DocentReviewService.completeReview] has already fired the
     * REVIEW_COMPLETED event (in MONITOR mode it's persisted to the watched log; in AWAIT it's queued for the
     * agent to drain), so tearing the queue/log down here could drop it before the agent reads it.
     */
    fun endReview() {
        DocentReviewService.getInstance(project).trailPath = null
        trail = null
        loadError = null
        currentSectionIndex = -1
        currentFileIndex = 0
        visited.clear()
        FileEditorManager.getInstance(project).closeFile(OpenDocentReviewAction.getOrCreateFile(project))
        listeners.forEach { it.onModelChanged() }
    }

    companion object {
        const val TOOL_WINDOW_ID = "Code Review Docent"

        /** Headline of the synthesized catch-all section ([appendOtherChangesSection]); shared so a dimmed hunk
         *  this section claims is labelled "Other changes" rather than "Section N · …". */
        const val OTHER_CHANGES_HEADLINE = "Other changes"

        fun getInstance(project: Project): DocentReviewController =
            project.getService(DocentReviewController::class.java)

        /** Convenience for off-EDT callers (the MCP coroutine): marshal [openReview] onto the EDT. */
        fun openReviewLater(project: Project) =
            ApplicationManager.getApplication().invokeLater { getInstance(project).openReview() }
    }
}
