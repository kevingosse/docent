package com.kevingosse.docent.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Pair
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.trail.Anchor
import com.kevingosse.docent.trail.GitChangeSet
import com.kevingosse.docent.trail.Section
import com.kevingosse.docent.trail.Trail
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The review surface (docs/DESIGN.md §9), hosted in the editor (middle) pane. Navigation lives in the left
 * "Docent" tool window ([DocentNavPanel]); this view renders whatever [DocentReviewController] selected:
 *
 *  - **Overview** (no section) — the trailhead: a title page with the thesis and the route (docs/UI.md §4).
 *  - **A section, no file** — the section's narration over the "discuss this section" strip (the read-once summary).
 *  - **A file under a section** — *only* that file's diff, full editor height.
 *
 * A slim **trail header** (segmented progress + file chips) sits above both section views so position on the
 * trail stays ambient (docs/UI.md §5). Splitting the summary from the file diffs keeps the read-once narration
 * from permanently eating the pane. The per-section conversation is kept alive across summary↔file switches so
 * diving into a file and back doesn't wipe the chat. The diff comes from git (`commit~1`) vs the live working
 * tree, so it shows faithfully even when the tree has moved on (added/deleted files render as all-added /
 * all-removed).
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

    /** Derived scope stats (file count, +/- totals, per-file counts) for the trailhead + chips. Computed off-EDT
     *  once per diff base ([statsKey]); null until the git calls land, then a re-render fills them in. */
    private data class TrailStats(val files: Int, val additions: Int, val deletions: Int, val perFile: Map<String, kotlin.Pair<Int, Int>>)
    private var stats: TrailStats? = null
    private var statsKey: String? = null

    /** The width the trailhead was last rendered at — its wrap widths are baked into HTML, so a meaningful
     *  resize re-renders it (only while the overview is showing). */
    private var overviewWidth = 0

    init {
        controller.addListener(this)
        controller.ensureLoaded()
        render()
        controller.onReviewTabOpened() // the tab is now open → let the nav highlight the current selection
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (controller.trail != null && controller.currentSectionIndex < 0 &&
                    width > 0 && kotlin.math.abs(width - overviewWidth) >= JBUI.scale(32)
                ) render()
            }
        })
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
        ensureStats(j)
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
        val error = controller.loadError
        add(
            if (error != null) DocentUi.emptyState("Couldn't load the trail", error)
            else DocentUi.emptyState(
                "No review loaded",
                "Capture decisions while working, then call docent_finalize_trail to write the Trail and open the review.",
            ),
            BorderLayout.CENTER,
        )
        revalidate(); repaint()
    }

    // ----- Derived stats (files, +/-, per-file) — feed the trailhead and the chips -----

    private fun ensureStats(j: Trail) {
        val key = j.beforeRef() ?: return
        val base = project.basePath ?: return
        if (statsKey == key) return
        statsKey = key
        stats = null
        ApplicationManager.getApplication().executeOnPooledThread {
            val perFile = GitChangeSet.numstat(base, key)
            val files = GitChangeSet.forTrail(base, j).size.coerceAtLeast(perFile.size)
            val computed = TrailStats(
                files = files,
                additions = perFile.values.sumOf { it.first },
                deletions = perFile.values.sumOf { it.second },
                perFile = perFile,
            )
            ApplicationManager.getApplication().invokeLater(
                { if (statsKey == key) { stats = computed; render() } },
                ModalityState.any(),
            )
        }
    }

    // ----- The trailhead (the thesis as a title page — docs/UI.md §4) -----

    private fun showOverview(j: Trail) {
        removeAll()
        overviewWidth = width
        val contentWidth = (width - JBUI.scale(96)).coerceIn(JBUI.scale(320), JBUI.scale(620))

        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        // Title block: the subject, then a derived scope line — the "instinctive scope" of DESIGN §9.
        column.add(JBLabel("<html><div style='width:${contentWidth}px'>${DocentUi.escapeHtml(j.subject)}</div></html>").apply {
            font = JBFont.h1()
            alignmentX = Component.LEFT_ALIGNMENT
        })
        column.add(JBLabel(scopeLineHtml(j, contentWidth)).apply {
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(6)
        })

        // The thesis, at a readable measure.
        column.add(DocentUi.htmlPane(j.thesis, contentWidth).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(16)
        })

        // The single loud element on the page.
        column.add(startButtonRow(j))

        // The route: one card per section — the book's table of contents.
        if (j.sections.isNotEmpty()) {
            column.add(JBLabel("THE ROUTE").apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(18, 0, 6, 0)
            })
            j.sections.forEachIndexed { i, section -> column.add(sectionCard(i, section, contentWidth)) }
        }

        val wrapper = JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(column, GridBagConstraints().apply {
                gridx = 0; gridy = 0
                weightx = 1.0; weighty = 1.0
                anchor = GridBagConstraints.NORTH
                insets = JBUI.insets(28, 28, 28, 28)
            })
        }
        add(scrolled(wrapper), BorderLayout.CENTER)
        revalidate(); repaint()
    }

    /** "7 sections · 23 files · +412 −168 · vs HEAD (working tree)" — all derived, nothing authored. */
    private fun scopeLineHtml(j: Trail, contentWidth: Int): String {
        val n = j.sections.size
        val parts = mutableListOf("$n section${if (n == 1) "" else "s"}")
        stats?.let { s ->
            parts += "${s.files} file${if (s.files == 1) "" else "s"}"
            DocentUi.plusMinusHtml(s.additions, s.deletions).takeIf { it.isNotEmpty() }?.let { parts += it }
        }
        parts += if (j.isWorkingTree()) "working tree vs ${DocentUi.escapeHtml(j.baseRef ?: "")}"
        else "commit ${DocentUi.escapeHtml((j.commit ?: "").take(8))}"
        return "<html><div style='width:${contentWidth}px'>${parts.joinToString("&nbsp;&nbsp;·&nbsp;&nbsp;")}</div></html>"
    }

    /** "Start the walkthrough" (or "Continue…" once sections were visited) as the page's default button. */
    private fun startButtonRow(j: Trail): JComponent {
        val visitedAny = controller.visited.any { it >= 0 }
        val target = if (!visitedAny) 0 else (j.sections.indices.firstOrNull { it !in controller.visited } ?: 0)
        val button = JButton(if (visitedAny) "Continue the walkthrough" else "Start the walkthrough").apply {
            putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true)
            isEnabled = j.sections.isNotEmpty()
            addActionListener { controller.selectSection(target) }
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyTop(18)
            add(button)
        }
    }

    /** One route card: number (or ✓ once visited), headline, derived meta. Click → the section. */
    private fun sectionCard(index: Int, section: Section, contentWidth: Int): JComponent {
        val visited = index in controller.visited
        val badge =
            if (visited) JBLabel(AllIcons.Actions.Commit)
            else JBLabel("${index + 1}").apply {
                font = JBFont.h3()
                foreground = DocentUi.DOCENT
            }
        val west = JPanel(GridBagLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(JBUI.scale(30), JBUI.scale(30))
            add(badge, GridBagConstraints())
        }

        val textWidth = contentWidth - JBUI.scale(60)
        val files = section.anchors.size
        val comments = section.anchors.sumOf { it.comments?.size ?: 0 }
        val meta = buildList {
            add("$files file${if (files == 1) "" else "s"}")
            if (comments > 0) add("$comments comment${if (comments == 1) "" else "s"}")
        }.joinToString("  ·  ")
        val center = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("<html><div style='width:${textWidth}px'><b>${DocentUi.escapeHtml(section.headline)}</b></div></html>").apply {
                alignmentX = Component.LEFT_ALIGNMENT
            })
            add(JBLabel(meta).apply {
                font = JBUI.Fonts.smallFont()
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(2)
            })
        }

        val card = DocentUi.RoundedPanel(null).apply {
            border = DocentUi.cardBorder(JBColor.border())
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            add(west, BorderLayout.WEST)
            add(center, BorderLayout.CENTER)
        }
        DocentUi.installClick(card) { controller.selectSection(index) }
        DocentUi.installHover(card)
        // Space the route cards apart; cap the height so BoxLayout doesn't stretch the last card.
        val row = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(6)
            add(card, BorderLayout.CENTER)
        }
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
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

        // The trail header keeps position ambient in both views: progress strip, section title, file chips.
        add(trailHeader(j, index, section, fileIndex), BorderLayout.NORTH)

        if (fileIndex < 0) {
            // Section summary: read the narration, chat with the Docent. No diff (that's a per-file step).
            summaryComponent?.let { add(it, BorderLayout.CENTER) }
        } else {
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

    // ----- The trail header (ambient position — docs/UI.md §5) -----

    /**
     * A slim strip over both section views: the segmented trail progress, "Section N of M · headline", and a
     * wrap-row of file chips (file-type icon + name + derived ±). The current file's chip is filled; clicking a
     * chip opens that file's diff. Replaces the old summary-only button bar, so the *diff* view keeps its
     * bearings too.
     */
    private fun trailHeader(j: Trail, sectionIndex: Int, section: Section, fileIndex: Int): JComponent {
        val titleRow = JPanel(BorderLayout(JBUI.scale(10), 0)).apply { isOpaque = false }
        titleRow.add(
            JPanel(GridBagLayout()).apply {
                isOpaque = false
                add(DocentUi.SegmentedProgress(j.sections.size, controller.visited.filterTo(mutableSetOf()) { it >= 0 }, sectionIndex), GridBagConstraints())
            },
            BorderLayout.WEST,
        )
        titleRow.add(
            JPanel().apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JBLabel("Section ${sectionIndex + 1} of ${j.sections.size}").apply {
                    font = JBUI.Fonts.smallFont()
                    foreground = JBColor.GRAY
                })
                add(javax.swing.Box.createHorizontalStrut(JBUI.scale(10)))
                add(JBLabel(section.headline).apply { font = JBFont.label().asBold() })
            },
            BorderLayout.CENTER,
        )

        val chipsRow: JComponent = when {
            section.anchors.isEmpty() -> JBLabel("No files in this section.").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyTop(6)
            }
            section.anchors.size > 14 -> JBLabel("${section.anchors.size} files — pick one from the rail on the left.").apply {
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyTop(6)
            }
            else -> JPanel(DocentUi.WrapLayout(JBUI.scale(6), JBUI.scale(6))).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(4)
                section.anchors.forEachIndexed { i, anchor -> add(fileChip(sectionIndex, i, anchor, selected = i == fileIndex)) }
            }
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(8, 12, 8, 12),
            )
            titleRow.alignmentX = Component.LEFT_ALIGNMENT
            titleRow.maximumSize = Dimension(Int.MAX_VALUE, titleRow.preferredSize.height)
            chipsRow.alignmentX = Component.LEFT_ALIGNMENT
            add(titleRow)
            add(chipsRow)
        }
    }

    /** A file chip: file-type icon + name (+ derived ± once stats land). Filled when it's the open file. */
    private fun fileChip(sectionIndex: Int, fileIndex: Int, anchor: Anchor, selected: Boolean): JComponent {
        val name = anchor.path.substringAfterLast('/')
        val icon = FileTypeManager.getInstance().getFileTypeByFileName(name).icon
        val pm = stats?.perFile?.get(anchor.path)
            ?.let { DocentUi.plusMinusHtml(it.first, it.second) }.orEmpty()
        val html = "<html>${DocentUi.escapeHtml(name)}${if (pm.isNotEmpty()) "&nbsp;&nbsp;<small>$pm</small>" else ""}</html>"
        val label = JBLabel(html).apply {
            this.icon = icon
            iconTextGap = JBUI.scale(4)
        }
        val chip = DocentUi.RoundedPanel(if (selected) DocentUi.DOCENT_MSG_BG else null, 12).apply {
            border = javax.swing.BorderFactory.createCompoundBorder(
                com.intellij.ui.RoundedLineBorder(if (selected) DocentUi.DOCENT else JBColor.border(), JBUI.scale(12)),
                JBUI.Borders.empty(3, 8),
            )
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = anchor.path
            add(label, BorderLayout.CENTER)
        }
        DocentUi.installClick(chip) { controller.selectFile(sectionIndex, fileIndex) }
        if (!selected) DocentUi.installHover(chip)
        return chip
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

        host.add(DocentUi.loadingState("Loading diff for ${anchor.path.substringAfterLast('/')}…"), BorderLayout.CENTER)
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
    private fun commentPoster(anchor: Anchor): CommentPoster = CommentPoster { thread, reviewerText, onReply, onStalled ->
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
            onReply = { reply ->
                ApplicationManager.getApplication().invokeLater({ onReply(reply) }, ModalityState.any())
            },
            onStalled = { eventId ->
                ApplicationManager.getApplication().invokeLater({ onStalled { service.nudge(eventId) } }, ModalityState.any())
            },
        )
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

    private fun message(text: String): JComponent =
        DocentUi.htmlPane("<p>${DocentUi.escapeHtml(text).replace("\n", "<br>")}</p>")
            .apply { border = JBUI.Borders.empty(12) }

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
