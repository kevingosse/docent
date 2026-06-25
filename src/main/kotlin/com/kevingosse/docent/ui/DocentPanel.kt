package com.kevingosse.docent.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.trail.Anchor
import com.kevingosse.docent.trail.Trail
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * The review surface (docs/DESIGN.md §9), hosted in the editor (middle) pane. Navigation lives in the left
 * "Docent" tool window ([DocentNavPanel]); this view renders whatever [DocentReviewController] selected:
 *
 *  - **Overview** (no section) — the thesis.
 *  - **A section, no file** — the section's narration over the "discuss this section" strip (the read-once summary).
 *  - **A file under a section** — *only* that file's diff, full editor height.
 *
 * Splitting the summary from the file diffs keeps the read-once narration from permanently eating the pane.
 * The per-section conversation is kept alive across summary↔file switches so diving into a file and back doesn't
 * wipe the chat. The diff comes from git (`commit~1`) vs the live working tree, so it shows faithfully even
 * when the tree has moved on (added/deleted files render as all-added / all-removed).
 */
class DocentPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, DocentReviewController.Listener {

    private val controller = DocentReviewController.getInstance(project)

    /**
     * In-session comment threads, keyed by file path and seeded from the Trail. Shared (by reference) with
     * the diff extension so a thread added/edited in one viewer survives switching tool or file.
     */
    private val threadsByPath = mutableMapOf<String, MutableList<CommentThread>>()

    // Per-section components, kept alive while the section is unchanged so switching summary↔file is cheap.
    /** The section summary view: narration over the conversation. */
    private var summaryComponent: JComponent? = null
    private var conversation: SectionConversationPanel? = null
    /** The container the diff is mounted into. */
    private var diffHost: JPanel? = null
    private var diffProcessor: SectionDiffProcessor? = null
    /** Guards against a slow git fetch landing after the user has moved on. */
    private var diffSeq = 0
    /** Which section's components are currently built (-1/MIN means none). */
    private var renderedSectionIndex = Int.MIN_VALUE
    /** Which file's diff [diffHost] currently shows, so returning to it doesn't reload. */
    private var loadedDiffFileIndex = -1

    init {
        controller.addListener(this)
        controller.ensureLoaded()
        render()
        controller.onReviewTabOpened() // the tab is now open → let the nav highlight the current selection
    }

    override fun onModelChanged() {
        threadsByPath.clear()
        disposeSection()
        renderedSectionIndex = Int.MIN_VALUE
        render()
    }

    override fun onSelectionChanged() = render()

    /**
     * The agent connection flipped. Rebuild the current section so its conversation input and inline-comment "+"
     * re-evaluate the connected state (the section diff re-renders, re-installing or hiding the "+"). Threads are
     * preserved (not cleared), so seeded comments and any reviewer cards survive.
     */
    override fun onConnectionChanged() {
        disposeSection()
        renderedSectionIndex = Int.MIN_VALUE
        render()
    }

    private fun render() {
        val j = controller.trail
        if (j == null) {
            renderEmpty()
            return
        }
        val sectionIndex = controller.currentSectionIndex
        if (sectionIndex < 0) {
            disposeSection()
            renderedSectionIndex = -1
            showOverview(j)
            return
        }
        if (sectionIndex != renderedSectionIndex) rebuildSection(j, sectionIndex)
        showSectionView(j, sectionIndex)
    }

    private fun renderEmpty() {
        disposeSection()
        renderedSectionIndex = Int.MIN_VALUE
        removeAll()
        add(
            message(
                controller.loadError?.let { "Couldn't load the trail:\n$it" }
                    ?: "No review loaded.\n\nCapture decisions while working, then call " +
                    "docent_finalize_trail to write the Trail and open the review.",
            ),
            BorderLayout.CENTER,
        )
        revalidate(); repaint()
    }

    // ----- Overview (the thesis) -----

    private fun showOverview(j: Trail) {
        removeAll()
        add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Start review →").apply {
                    isEnabled = j.sections.isNotEmpty()
                    addActionListener { controller.selectSection(0) }
                })
            },
            BorderLayout.NORTH,
        )
        add(scrolled(htmlPane(j.thesis).apply { border = JBUI.Borders.empty(12) }), BorderLayout.CENTER)
        revalidate(); repaint()
    }

    // ----- A section: summary (narration + conversation) OR a single file's diff -----

    /** (Re)build the per-section components when the section changes. */
    private fun rebuildSection(j: Trail, index: Int) {
        disposeSection()
        val section = j.sections.getOrNull(index) ?: return

        // The section summary IS the conversation: it opens with the narration (the Docent's first message) and
        // the human continues from there. No separate narration pane.
        val conv = SectionConversationPanel(project, section, index)
        conversation = conv
        Disposer.register(this, conv)
        summaryComponent = conv

        diffHost = JPanel(BorderLayout())
        loadedDiffFileIndex = -1
        renderedSectionIndex = index
    }

    private fun showSectionView(j: Trail, index: Int) {
        val section = j.sections.getOrNull(index) ?: return
        val fileIndex = controller.currentFileIndex
        removeAll()

        if (fileIndex < 0) {
            // Section summary: read the narration, chat with the Docent. No diff (that's a per-file step).
            // The bar offers file chips — jump straight into any of the section's files (no Overview/Prev/Next:
            // we're already at the section's overview, and step nav lives in each file's diff toolbar).
            add(summaryBar(j, index, section), BorderLayout.NORTH)
            summaryComponent?.let { add(it, BorderLayout.CENTER) }
        } else {
            // File diff: Overview/Prev/Next live in the diff viewer's own toolbar (SectionDiffProcessor),
            // so no separate top bar — the diff fills the pane.
            val host = diffHost
            if (host != null) {
                add(host, BorderLayout.CENTER)
                if (fileIndex != loadedDiffFileIndex) {
                    loadedDiffFileIndex = fileIndex
                    val anchor = section.anchors.getOrNull(fileIndex)
                    if (anchor == null) {
                        host.removeAll()
                        host.add(message("(no such file in this section)"), BorderLayout.CENTER)
                    } else {
                        showDiffFor(anchor)
                    }
                }
            }
        }
        revalidate(); repaint()
    }

    /**
     * The bar over a section's summary/conversation: a chip per file in the section that opens that file's diff
     * directly. No Overview (the left nav has the thesis row, and the summary *is* the section's overview) and no
     * Prev/Next (linear step nav lives in each file's diff toolbar via [SectionDiffProcessor]).
     */
    private fun summaryBar(j: Trail, index: Int, section: com.kevingosse.docent.trail.Section): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            if (section.anchors.isEmpty()) {
                add(JBLabel("No files in this section.").apply { foreground = JBColor.GRAY })
            } else {
                add(JBLabel("Files:").apply { foreground = JBColor.GRAY })
                section.anchors.forEachIndexed { fileIndex, anchor ->
                    add(JButton(anchor.path.substringAfterLast('/')).apply {
                        toolTipText = anchor.path
                        addActionListener { controller.selectFile(index, fileIndex) }
                    })
                }
            }
            add(JBLabel("Section ${index + 1} of ${j.sections.size}").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyLeft(8)
            })
        }

    // ----- The diff -----

    private fun showDiffFor(anchor: Anchor) {
        val host = diffHost ?: return
        disposeDiff()
        host.removeAll()

        val base = project.basePath
        val trail = controller.trail
        // "before" ref = baseRef (uncommitted work) or commit~1 (a committed Trail); see Trail.beforeRef().
        val beforeRef = trail?.beforeRef()
        if (base == null) {
            host.add(message("Open the reviewed solution first."), BorderLayout.CENTER)
            host.revalidate(); host.repaint(); return
        }
        if (beforeRef == null) {
            host.add(message("This trail has no \"baseRef\" or \"commit\" set, so there's nothing to diff against."), BorderLayout.CENTER)
            host.revalidate(); host.repaint(); return
        }
        val afterLabel = trail.afterLabel()

        host.add(
            JBLabel("Loading diff for ${anchor.path.substringAfterLast('/')}…")
                .apply { border = JBUI.Borders.empty(12); foreground = JBColor.GRAY },
            BorderLayout.CENTER,
        )
        host.revalidate(); host.repaint()

        val seq = ++diffSeq
        val path = anchor.path
        // Only the "before" side needs git; "after" is the live working-tree file (real document/VFS), which
        // keeps the diff navigable — Ctrl-click resolves through the IDE backend. For a committed Trail the
        // tree is checked out at `commit`; for a captured (uncommitted) one it carries the un-committed change.
        ApplicationManager.getApplication().executeOnPooledThread {
            val before = gitShow(beforeRef, path, base)
            ApplicationManager.getApplication().invokeLater(
                {
                    if (seq == diffSeq && diffHost === host) renderDiff(host, anchor, beforeRef, afterLabel, before)
                },
                ModalityState.any(),
            )
        }
    }

    private fun renderDiff(host: JPanel, anchor: Anchor, beforeRef: String, afterLabel: String, before: String?) {
        host.removeAll()
        val factory = DiffContentFactory.getInstance()
        val name = anchor.path.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)

        // "After" = the real file in the working tree (live document => navigation + highlighting). Missing =>
        // the commit deleted it; fall back to an empty side so it reads as all-removed.
        val vfile = LocalFileSystem.getInstance().findFileByPath("${project.basePath}/${anchor.path}")
        val afterDoc = vfile?.let { FileDocumentManager.getInstance().getDocument(it) }
        val hasAfter = vfile != null && afterDoc != null

        if (before == null && !hasAfter) {
            host.add(message("Not at the base ($beforeRef), and not in the working tree:\n${anchor.path}"), BorderLayout.CENTER)
        } else {
            val beforeContent = factory.create(project, before ?: "", fileType)
            val afterContent =
                if (hasAfter) factory.create(project, afterDoc, vfile)
                else factory.create(project, "", fileType)
            val request = SimpleDiffRequest(anchor.label ?: anchor.path, beforeContent, afterContent, beforeRef, afterLabel)
            // Both sides read-only: a review of a finished commit, not a merge. Suppresses the apply/revert
            // "accept change" gutter the side-by-side viewer shows for a writable side. Navigation unaffected.
            request.putUserData(DiffUserDataKeys.FORCE_READ_ONLY_CONTENTS, booleanArrayOf(true, true))
            // Hand the shared thread list to DocentDiffCommentExtension: seeds the cards and lets the gutter
            // "+" add new ones. Passed for every file so "+" works even where nothing is seeded.
            request.putUserData(DocentDiffMarker.THREADS, threadsFor(anchor))
            // Route reviewer remarks on this file's comments to the live Docent (no-op without an agent).
            request.putUserData(DocentDiffMarker.POSTER, commentPoster(anchor))
            // Interactive comment affordances (the gutter "+", card reply/compose inputs) are enabled only when an
            // agent is connected; otherwise seeded comments show read-only and no new ones can be added.
            request.putUserData(DocentDiffMarker.INTERACTIVE, DocentReviewService.getInstance(project).reviewActive)

            // When the anchor declares focus spans, drive the dimming overlay + section-scoped toolbar.
            // DocentDimmingDiffExtension reads RANGES/CONTROLLER; SectionDiffProcessor reads CONTROLLER off the
            // active request to decide whether to swap in the section-scoped nav.
            val ranges = anchor.focusRanges()
            if (ranges.isNotEmpty()) {
                request.putUserData(DocentFocusMarker.RANGES, ranges)
                request.putUserData(DocentFocusMarker.CONTROLLER, DimmingController())
                request.putUserData(DocentFocusMarker.OWNERS, regionOwnersFor(anchor.path))
                otherChangesNavFor(anchor.path)?.let { request.putUserData(DocentFocusMarker.OTHER_CHANGES_NAV, it) }
                request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, Pair.create(Side.RIGHT, ranges.first().first - 1))
            }

            // Host our own diff processor embedded in the editor pane (not a separate window): it keeps the
            // viewer switcher etc., but swaps the built-in next/prev for our section-scoped nav.
            val processor = SectionDiffProcessor(project, SimpleDiffRequestChain(request))
            diffProcessor = processor
            host.add(processor.component, BorderLayout.CENTER)
            processor.updateRequest(true, null)
        }
        host.revalidate()
        host.repaint()
    }

    /**
     * The mutable thread list for a file, seeded from the Trail on first use and reused thereafter. Comments
     * are gathered across *every* section that anchors this file (a file may be split across sections), so all of a
     * file's comments are present whichever section opened it; the diff extension collapses the ones outside the
     * current section's focus.
     */
    private fun threadsFor(anchor: Anchor): MutableList<CommentThread> =
        threadsByPath.getOrPut(anchor.path) {
            commentsForFile(anchor.path).map { CommentThread(it.author ?: "docent", it.body, it.line) }.toMutableList()
        }

    /** Every Docent comment on [path], across all sections that anchor it. */
    private fun commentsForFile(path: String): List<com.kevingosse.docent.trail.Comment> =
        controller.trail?.sections.orEmpty()
            .flatMap { it.anchors }
            .filter { it.path == path }
            .flatMap { it.comments.orEmpty() }

    /**
     * The focus regions that *other* sections declare on [path] (the current section is excluded — its hunks aren't
     * dimmed). Lets [DocentDimmingDiffExtension] name the section that owns each dimmed hunk.
     */
    private fun regionOwnersFor(path: String): List<RegionOwner> {
        val sections = controller.trail?.sections ?: return emptyList()
        val current = controller.currentSectionIndex
        return sections.mapIndexedNotNull { i, section ->
            if (i == current) return@mapIndexedNotNull null
            val fileIndex = section.anchors.indexOfFirst { it.path == path }
            if (fileIndex < 0) return@mapIndexedNotNull null
            val ranges = section.anchors[fileIndex].focusRanges().ifEmpty { return@mapIndexedNotNull null }
            // The dimmed-hunk hint links to this same file under its owning section. Defer the navigation off the
            // editor's mouse-event dispatch (the click disposes this very diff while it re-renders).
            RegionOwner(i, section.headline, ranges) {
                ApplicationManager.getApplication().invokeLater { controller.selectFile(i, fileIndex) }
            }
        }
    }

    /**
     * Navigation for a dimmed hunk that no narrating section claims: open **this file** under the synthesized
     * "Other changes" section, exactly like the per-section links. [DocentReviewController.selectInOtherChanges]
     * adds a file entry on the fly if the catch-all didn't already collect it, so this never dead-ends on the
     * section summary. Returns null only when there's no "Other changes" section. Deferred off the editor's
     * mouse-event dispatch (the click disposes the very diff it re-renders), as in [regionOwnersFor].
     */
    private fun otherChangesNavFor(path: String): (() -> Unit)? {
        val sections = controller.trail?.sections ?: return null
        if (sections.none { it.headline == DocentReviewController.OTHER_CHANGES_HEADLINE }) return null
        return { ApplicationManager.getApplication().invokeLater { controller.selectInOtherChanges(path) } }
    }

    /**
     * Routes a reviewer's remark on one of [anchor]'s comments to the live Docent via the broker, and appends
     * the agent's reply back into the thread (on the EDT). A no-op when no agent is driving the review (then
     * comments stay local, as before).
     */
    private fun commentPoster(anchor: Anchor): CommentPoster = CommentPoster { thread, reviewerText, onReply ->
        val service = DocentReviewService.getInstance(project)
        if (!service.reviewActive) return@CommentPoster
        val section = controller.trail?.sections?.getOrNull(controller.currentSectionIndex)
        val context = buildString {
            appendLine("Inline comment on ${anchor.path}, line ${thread.line}.")
            appendLine("Thread so far:")
            appendLine("${thread.author}: ${thread.body}")
            thread.replies.forEach { appendLine("${it.author}: ${it.body}") }
        }.trim()
        service.postEvent(
            kind = DocentReviewService.KIND_COMMENT,
            sectionIndex = controller.currentSectionIndex,
            sectionHeadline = section?.headline ?: "",
            file = anchor.path,
            line = thread.line,
            context = context,
            text = reviewerText,
        ) { reply ->
            ApplicationManager.getApplication().invokeLater({ onReply(reply) }, ModalityState.any())
        }
    }

    /** Read one file's contents at a git revision; null if the path doesn't exist there (added/deleted). */
    private fun gitShow(rev: String, path: String, base: String): String? = try {
        val process = ProcessBuilder("git", "-C", base, "show", "$rev:$path").start()
        val bytes = process.inputStream.readBytes()
        process.errorStream.readBytes() // drain so the process can exit
        if (process.waitFor() != 0) null else String(bytes, Charsets.UTF_8).trimStart('﻿')
    } catch (t: Throwable) {
        null
    }

    // ----- helpers -----

    private fun scrolled(c: JComponent): JComponent =
        JBScrollPane(c).apply { border = JBUI.Borders.empty() }

    private fun htmlPane(bodyHtml: String): JEditorPane =
        JEditorPane("text/html", wrapHtml(bodyHtml)).apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }

    private fun wrapHtml(body: String): String {
        val f = JBUI.Fonts.label()
        val fg = ColorUtil.toHtmlColor(JBColor.foreground())
        return "<html><head><style>" +
            "body{font-family:'${f.family}';font-size:${f.size}pt;color:$fg;margin:0;padding:0;}" +
            "p{margin:0 0 10px 0;} ul{margin:0 0 10px 18px;padding:0;} li{margin:0 0 6px 0;}" +
            "code{font-family:monospace;}" +
            "</style></head><body>$body</body></html>"
    }

    private fun message(text: String): JComponent =
        htmlPane("<p>${escapeHtml(text).replace("\n", "<br>")}</p>").apply { border = JBUI.Borders.empty(12) }

    private fun escapeHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun disposeDiff() {
        diffProcessor?.let { Disposer.dispose(it) }
        diffProcessor = null
    }

    /** Tear down the current section's components (diff + conversation + summary). */
    private fun disposeSection() {
        disposeDiff()
        conversation?.let { Disposer.dispose(it) }
        conversation = null
        summaryComponent = null
        diffHost = null
        loadedDiffFileIndex = -1
    }

    override fun dispose() {
        controller.removeListener(this)
        controller.onReviewTabClosed() // tab closed → nav clears its selection highlight
        disposeSection()
    }
}
