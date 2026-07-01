package com.kevingosse.docent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kevingosse.docent.AgentSessionInfo
import com.kevingosse.docent.DecisionLog
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.ReviewEvent
import com.kevingosse.docent.deliveryModeForProvider
import com.kevingosse.docent.trail.Anchor
import com.kevingosse.docent.trail.Section
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * The navigation rail, hosted in the left "Docent" tool window (see [DocentToolWindowFactory]). Lists the
 * sections and, under the current section, the files it touches. Selecting either drives the review tab in the
 * editor pane via [DocentReviewController] — this panel only writes selection and reflects it.
 *
 * With no trail loaded it becomes the **on-demand review launcher** (see [buildStartReviewState]): it railroads
 * the reviewer toward the one session that recorded the pending work and tucks every other path behind a
 * collapsed "Not the right session?" link, so the common case is a single obvious action.
 */
class DocentNavPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, DocentReviewController.Listener {

    private val controller = DocentReviewController.getInstance(project)

    private val subject = JBLabel("").apply { border = JBUI.Borders.empty(10, 10, 6, 10) }
    private val planList = object : JPanel(), Scrollable {
        init { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private val scrollPane = JBScrollPane(planList).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    /** Width (px) available to a wrapping label in the no-trail surface — the viewport width minus row padding,
     *  tracked from viewport resizes. 0 until first laid out (labels render unwrapped until then). */
    private var contentWidth = 0

    /** The title of the session linked via the UI picker, for the status line (null when linked via the MCP
     *  handoff — we don't know its title then — or when not connected). */
    private var linkedTitle: String? = null

    /** The session we just asked to start a review (no trail loaded yet), so the empty state can show a
     *  "preparing…" line until its docent_finalize_trail loads the trail here. Cleared once a trail loads. */
    private var awaitingStartFrom: String? = null

    /** Whether the no-trail surface's "Not the right session?" alternatives are expanded. Collapsed by default so
     *  the railroaded primary action stands alone; the reviewer opts into the other paths. */
    private var showAlternatives = false

    /** A one-shot line shown on the no-trail surface right after a review completes (e.g. "the agent is
     *  implementing N changes…"). Cleared once a new trail loads or new decisions are recorded. */
    private var completionNote: String? = null

    /** Whether the mid-review "Connect a different agent" choices are expanded (collapsed by default so the
     *  Complete-review action stays the focus). Only used while connected; the not-connected state always shows
     *  the choices inline. */
    private var showConnectChoices = false

    init {
        add(subject, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        // Track the usable width so every HTML label wraps to the tool-window width (a JBLabel only wraps when
        // an explicit width is baked into its HTML). Rebuild on meaningful changes.
        planList.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val w = planList.width - JBUI.scale(24)
                if (w > 0 && kotlin.math.abs(w - contentWidth) >= JBUI.scale(8)) {
                    contentWidth = w
                    refreshList()
                }
            }
        })

        controller.addListener(this)
        controller.ensureLoaded()
        DocentReviewService.getInstance(project).onChangesUpdated = { scheduleRefresh() }
        // Live-refresh the "ready for review" empty state as the agent records decisions off-EDT (its scratchpad
        // grows). Only matters when no trail is loaded — that's the surface that lists sessions + pending counts.
        DecisionLog.getInstance(project).onUpdated = { scheduleRefresh(onlyIfNoTrail = true) }
        // React to chat-tab activation: activating a tab builds its terminal, which flips a session from inactive
        // to reachable — refresh the connect/session lists so it moves to the active group without a manual reopen.
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = onEditorSelectionChanged()
            },
        )
        rebuild()
    }

    /** A chat tab was (de)selected/opened. If the session lists are on screen, refresh so reachability updates —
     *  the terminal builds slightly after the tab is shown, so re-check once more after a short settle. */
    private fun onEditorSelectionChanged() {
        if (!sessionsVisible()) return
        scheduleRefresh()
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { ApplicationManager.getApplication().invokeLater({ if (sessionsVisible()) refreshList() }, ModalityState.any()) },
            500,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Whether the surface currently shows the workbench session lists (so tab-activation changes are worth a
     *  refresh): the no-trail launcher, an unconnected loaded trail, or the expanded connect picker. */
    private fun sessionsVisible(): Boolean {
        val service = DocentReviewService.getInstance(project)
        return controller.trail == null || !service.reviewActive || showConnectChoices
    }

    override fun onModelChanged() = rebuild()
    override fun onSelectionChanged() = refreshList()
    override fun onConnectionChanged() {
        // A disconnect drops the linked title; rebuild so the inline connection/actions reflect the new state
        // (and, with no trail, so a post-restart "not available" flips to the session list once the seams install).
        if (!DocentReviewService.getInstance(project).reviewActive) linkedTitle = null
        scheduleRefresh()
    }

    /** Coalesce an off-EDT change into an EDT list rebuild. [onlyIfNoTrail] skips the work while a trail is loaded. */
    private fun scheduleRefresh(onlyIfNoTrail: Boolean = false) {
        ApplicationManager.getApplication().invokeLater({
            if (onlyIfNoTrail && controller.trail != null) return@invokeLater
            refreshList()
        }, ModalityState.any())
    }

    private fun rebuild() {
        val j = controller.trail
        if (j != null) {
            awaitingStartFrom = null // a trail loaded — the "preparing…" line is done its job
            completionNote = null    // …and a fresh review supersedes any "review complete" note
        }
        subject.text = if (j != null) wrapHtml("<b>${escapeHtml(j.subject)}</b>") else ""
        refreshList()
    }

    private fun refreshList() {
        planList.removeAll()
        val j = controller.trail
        if (j == null) {
            buildStartReviewState()
        } else {
            planList.add(overviewRow())
            j.sections.forEachIndexed { i, section ->
                planList.add(planRow(i, section))
                // Expand a section's files only while its review is actually open in the editor.
                if (controller.reviewTabOpen && i == controller.currentSectionIndex) {
                    section.anchors.forEachIndexed { fi, anchor -> planList.add(fileRow(i, fi, anchor)) }
                }
            }
            buildReviewFooter()
        }
        planList.revalidate()
        planList.repaint()
    }

    /**
     * The no-trail surface — the on-demand review launcher (DESIGN: don't auto-open after every change). It
     * railroads: when the recorded decisions clearly belong to one live session it leads with a single "Start
     * reviewing in …" card and hides every alternative behind "Not the right session?". Sessions are shown
     * *only* when there's pending work to review (an empty session is never a startable option here).
     */
    private fun buildStartReviewState() {
        // A load error wins — the user explicitly tried to open a trail that didn't read.
        controller.loadError?.let {
            planList.add(messageLabel("Couldn't load the trail:\n$it"))
            planList.add(loadTrailLink())
            return
        }

        val service = DocentReviewService.getInstance(project)
        val directory = service.sessionDirectory
        val workbenchReady = directory != null && service.eventNotifier != null
        val total = DecisionLog.getInstance(project).count()

        if (total > 0) completionNote = null // new work recorded — the just-completed note is stale, move on

        // Nothing recorded yet → a single calm message (or the just-completed note). Don't surface sessions:
        // there's nothing to review, so an empty "New Thread" tab must never masquerade as a startable review.
        if (total == 0) {
            planList.add(messageLabel(completionNote ?:
                "No changes to review yet.\n\nAs the coding agent works it records the decisions behind each " +
                    "change. When there's something to review, it'll appear here and you can start the walkthrough.",
            ))
            if (!workbenchReady) {
                planList.add(messageLabel("Agent Workbench isn't connected — you can still open a saved trail:"))
            }
            planList.add(loadTrailLink())
            return
        }

        val counts = DecisionLog.getInstance(project).countsBySession()
        val sessions = directory?.listSessions().orEmpty()

        // Work is pending but no live session is visible to host it (workbench absent, or the authoring tab is gone).
        if (!workbenchReady || sessions.isEmpty()) {
            val reason = if (!workbenchReady) " Agent Workbench isn't connected."
            else " Open the session that made the changes and it'll appear here."
            planList.add(messageLabel(
                "$total ${decisions(total)} ready to review — but I can't see the session that made them.$reason",
            ))
            planList.add(messageLabel("Ask the agent to call docent_finalize_trail, or open a saved trail:"))
            planList.add(loadTrailLink())
            return
        }

        awaitingStartFrom?.let { planList.add(messageLabel("⟳  Asked $it to prepare the review…")) }

        val likely = pickLikelySession(sessions, counts)
        if (likely != null) {
            planList.add(messageLabel(
                "$total ${decisions(total)} ready to review. Start the walkthrough in the session that made them:",
            ))
            planList.add(primaryStartRow(likely, counts[likely.threadId] ?: 0))
            val others = sessions.filter { it.threadId != likely.threadId }
            planList.add(alternativesToggle())
            if (showAlternatives) {
                if (others.isNotEmpty()) planList.add(sectionHeader("Other sessions"))
                others.sortedByDescending { counts[it.threadId] ?: 0 }
                    .forEach { planList.add(alternativeSessionRow(it, counts[it.threadId] ?: 0)) }
                newSessionRows { p, l -> startFreshSessionWith(p, l) }.forEach { planList.add(it) }
                planList.add(loadTrailLink())
            }
            return
        }

        // Several sessions, none clearly the author → no railroad; present the picker plainly.
        planList.add(messageLabel("$total ${decisions(total)} ready to review. Pick the session that made the changes:"))
        planList.add(sectionHeader("Sessions"))
        sessions.sortedByDescending { counts[it.threadId] ?: 0 }
            .forEach { planList.add(alternativeSessionRow(it, counts[it.threadId] ?: 0)) }
        newSessionRows { p, l -> startFreshSessionWith(p, l) }.forEach { planList.add(it) }
        planList.add(loadTrailLink())
    }

    /**
     * The session to railroad the reviewer toward, or null when it's genuinely ambiguous (show a picker instead).
     * Confident when exactly one session recorded pending decisions, when one clearly leads the others, or when
     * there's only a single live session at all.
     */
    private fun pickLikelySession(sessions: List<AgentSessionInfo>, counts: Map<String, Int>): AgentSessionInfo? {
        val withPending = sessions.filter { (counts[it.threadId] ?: 0) > 0 }
            .sortedByDescending { counts[it.threadId] ?: 0 }
        return when {
            withPending.size == 1 -> withPending.first()
            // A clear leader: strictly more pending decisions than the runner-up.
            withPending.size > 1 && (counts[withPending[0].threadId] ?: 0) > (counts[withPending[1].threadId] ?: 0) -> withPending.first()
            // Only one live session at all — weak signal, but the only candidate.
            sessions.size == 1 -> sessions.first()
            else -> null
        }
    }

    // --- Trail-loaded footer (inline; no bottom button bar) -----------------------------------------------------

    /**
     * The connection status + review actions, appended inline to the bottom of the plan rail (DESIGN: everything
     * inline — no popups, no fixed bottom button bar). Mid-review the primary action is a **Complete review** card
     * with the agent picker tucked behind a collapsed "Connect a different agent" toggle; before an agent is
     * connected, connecting *is* the task, so the agent choices render inline directly. "Load a different trail" is
     * hidden mid-review — swapping the trail out from under a live review makes no sense.
     */
    private fun buildReviewFooter() {
        val service = DocentReviewService.getInstance(project)
        planList.add(divider())
        val connected = service.reviewActive
        val statusText =
            if (connected) "● ${linkedTitle?.let { "Connected: ${cropTitle(it)}" } ?: "Agent connected"}" else "○ Not connected"
        planList.add(statusRow(statusText, connected))

        if (connected) {
            val n = service.queuedChanges().size
            val subtitle = if (n == 0) "No changes queued" else "$n ${changes(n)} queued"
            planList.add(primaryCard("Complete review", subtitle, AllIcons.Actions.Checked, "Finish the review") { completeReview() })
        }

        // One bordered card that expands in place: collapsed it's a "Connect an agent" trigger, expanded it holds
        // the choices with a close ✕ — same box either way (no popup, no jarring layout swap).
        planList.add(connectPanel(connected))

        if (!connected) planList.add(actionLinkRow("Load a different trail…") { loadTrail() })
    }

    /**
     * The connect UI as a single bordered card. Collapsed: a clickable "Connect an agent" title that expands it.
     * Expanded: the same card, now holding a header row (title + close ✕) and the inline choices. Green-bordered
     * (primary) when no agent is connected yet, neutral (secondary) mid-review beside the Complete-review card.
     */
    private fun connectPanel(connected: Boolean): JComponent {
        val title = if (connected) "Connect a different agent" else "Connect an agent"
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        // Max height must track the *laid-out* preferred height: the expanded card holds wrapping message labels
        // whose height isn't known until they have a width, so a fixed max (computed at build time) clips them.
        val card = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            border = cardBorder(if (connected) JBColor.border() else START_BORDER)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(content, BorderLayout.CENTER)
        }
        if (showConnectChoices) {
            content.add(connectHeaderRow(title))
            buildConnectChoicesInto(content)
        } else {
            val icon = AllIcons.Actions.Execute
            content.add(JBLabel(wrapHtml("<b>${escapeHtml(title)}</b>", icon.iconWidth + JBUI.scale(40))).apply {
                this.icon = icon
                iconTextGap = JBUI.scale(6)
                alignmentX = Component.LEFT_ALIGNMENT
            })
            card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            card.toolTipText = "Connect an agent to this trail"
            installClick(card) { showConnectChoices = true; refreshList() }
            installHover(card)
        }
        return card
    }

    /** The expanded connect card's header: bold title on the left, a close ✕ on the right that collapses it. */
    private fun connectHeaderRow(title: String): JComponent {
        val close = JBLabel(AllIcons.Actions.CloseHovered).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Close"
            border = JBUI.Borders.emptyLeft(8)
        }
        installClick(close) { showConnectChoices = false; refreshList() }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel(wrapHtml("<b>${escapeHtml(title)}</b>")), BorderLayout.CENTER)
            add(close, BorderLayout.EAST)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    // --- No-trail-surface rows ----------------------------------------------------------------------------------

    /** The railroaded primary action: a bordered, clickable card leading the reviewer into the likely session. */
    private fun primaryStartRow(s: AgentSessionInfo, pending: Int): JComponent {
        val title = "Start reviewing in “${cropTitle(s.title)}”"
        val subParts = buildList {
            if (!s.reachable) add("inactive — open its tab first")
            if (pending > 0) add("$pending ${decisions(pending)}")
            relativeTime(s.updatedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        return primaryCard(title, subParts.joinToString("  ·  ").ifEmpty { null },
            AllIcons.Actions.Execute, "Start the Docent walkthrough on this session") { sendStartReview(s) }
    }

    /**
     * A bordered, clickable card for the *primary* action of a surface — visually louder than the plain
     * [actionLinkRow] links around it, so the reviewer's eye lands on the one normal next step.
     */
    private fun primaryCard(title: String, subtitle: String?, icon: Icon, tooltip: String?, action: () -> Unit): JComponent {
        val titleLabel = JBLabel(wrapHtml("<b>${escapeHtml(title)}</b>", icon.iconWidth + JBUI.scale(30))).apply {
            this.icon = icon
            iconTextGap = JBUI.scale(6)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            if (!subtitle.isNullOrEmpty()) add(JBLabel(subtitle).apply {
                foreground = JBColor.GRAY
                font = JBUI.Fonts.smallFont()
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyTop(2)
            })
        }
        val card = JPanel(BorderLayout()).apply {
            border = cardBorder(START_BORDER)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            add(content, BorderLayout.CENTER)
            if (tooltip != null) toolTipText = tooltip
        }
        installClick(card) { action() }
        installHover(card)
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    /** Border for the emphasized cards: an [accent] line box with inner padding, plus an outer margin so the card
     *  isn't flush against the rail edges. */
    private fun cardBorder(accent: Color) = JBUI.Borders.compound(
        JBUI.Borders.empty(4, 8),
        JBUI.Borders.compound(JBUI.Borders.customLine(accent), JBUI.Borders.empty(8, 10)),
    )

    /** A de-emphasized alternative session row: pick this one instead. Clicking starts the review on it. */
    private fun alternativeSessionRow(s: AgentSessionInfo, pending: Int): JComponent {
        val title = escapeHtml(cropTitle(s.title))
        val parts = buildList {
            if (!s.reachable) add("inactive")
            if (pending > 0) add("$pending pending")
            relativeTime(s.updatedAt).takeIf { it.isNotEmpty() }?.let { add(it) }
        }
        val suffix = if (parts.isEmpty()) "" else
            "&nbsp;&nbsp;<font color=\"#808080\">·&nbsp;" + parts.joinToString("&nbsp;·&nbsp;") { escapeHtml(it) } + "</font>"
        val label = JBLabel(wrapHtml("&#x25B8;&nbsp;&nbsp;$title$suffix")).apply {
            toolTipText = if (s.reachable) "Start the Docent review on this session"
            else "Open this session's tab to activate it, then start the review"
        }
        return clickableRow(label, false, JBUI.Borders.empty(4, 10)) { sendStartReview(s) }
    }

    /** The collapsed/expanded toggle for the alternative paths. */
    private fun alternativesToggle(): JComponent =
        actionLinkRow("Not the right session?  " + if (showAlternatives) "▾" else "▸") {
            showAlternatives = !showAlternatives
            refreshList()
        }

    private fun loadTrailLink(): JComponent = actionLinkRow("Load a saved trail…") { loadTrail() }

    /** A left-aligned inline link row (replaces the old bottom buttons). */
    private fun actionLinkRow(text: String, action: () -> Unit): JComponent =
        ActionLink(text) { action() }.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(5, 10)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun divider(): JComponent = JPanel().apply {
        isOpaque = false
        border = JBUI.Borders.customLineTop(JBColor.border())
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(12))
        preferredSize = Dimension(1, JBUI.scale(12))
    }

    private fun statusRow(text: String, connected: Boolean): JComponent =
        JBLabel(text).apply {
            foreground = if (connected) CONNECTED else JBColor.GRAY
            border = JBUI.Borders.empty(2, 10, 2, 10)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    /** A wrapped, gray prose line for the no-trail surface (instructions / status). */
    private fun messageLabel(text: String): JComponent =
        WrappingMessageArea(text).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(8, 10)
            foreground = JBColor.GRAY
            font = UIUtil.getLabelFont()
            alignmentX = Component.LEFT_ALIGNMENT
        }


    /** JBTextArea wraps during painting, but BoxLayout asks preferred height before assigning final width.
     *  Size it to the current list width first so the message gets enough height and no horizontal clipping.
     *  [widthProvider] supplies that width when [parent] can't (e.g. nested in a card whose content panel has
     *  no laid-out width yet at measure time) — see [cardMessageLabel]. */
    private class WrappingMessageArea(text: String, private val widthProvider: (() -> Int)? = null) : JBTextArea(text) {
        override fun getPreferredSize(): Dimension {
            val available = widthProvider?.invoke()?.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: width
            if (available > 0) setSize(available, Short.MAX_VALUE.toInt())
            return super.getPreferredSize().apply { if (available > 0) width = available }
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

        override fun getAlignmentX(): Float = LEFT_ALIGNMENT
    }
    private fun sectionHeader(text: String): JComponent =
        JBLabel(text.uppercase()).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(12, 10, 4, 10)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    /** Wrap [inner] HTML so a [JBLabel] line-wraps to the tracked [contentWidth] (Swing labels only wrap when
     *  an explicit pixel width is given). Falls back to unconstrained HTML before the first layout sizes us. */
    private fun wrapHtml(inner: String, extraInset: Int = 0): String {
        val w = contentWidth - extraInset
        return if (w > JBUI.scale(60)) "<html><div style='width:${w}px'>$inner</div></html>"
        else "<html>$inner</html>"
    }

    private fun overviewRow(): JComponent {
        val isCurrent = controller.reviewTabOpen && controller.currentSectionIndex < 0
        val fg = if (isCurrent) UIUtil.getListSelectionForeground(true) else JBColor.foreground()
        val label = JBLabel(wrapHtml("<b>Overview</b>")).apply { foreground = fg }
        return clickableRow(label, isCurrent, JBUI.Borders.empty(6, 8)) { controller.showOverview() }
    }

    private fun planRow(index: Int, section: Section): JComponent {
        // Highlight the section row when showing its summary; a selected file highlights its own row instead.
        // Only when the review tab is actually open (a closed middle pane shows no selection).
        val isCurrent = controller.reviewTabOpen &&
            index == controller.currentSectionIndex && controller.currentFileIndex < 0
        val glyph = if (controller.visited.contains(index)) "&#x2713;" else "&#x25CB;" // ✓ / ○
        val fg = if (isCurrent) UIUtil.getListSelectionForeground(true) else JBColor.foreground()
        val label = JBLabel(wrapHtml(
            "$glyph&nbsp;&nbsp;<b>${index + 1}.</b>&nbsp;${escapeHtml(section.headline)}",
        )).apply { foreground = fg }
        return clickableRow(label, isCurrent, JBUI.Borders.empty(6, 8)) { controller.selectSection(index) }
    }

    private fun fileRow(sectionIndex: Int, fileIndex: Int, anchor: Anchor): JComponent {
        val isSelected = controller.reviewTabOpen &&
            sectionIndex == controller.currentSectionIndex && fileIndex == controller.currentFileIndex
        val name = anchor.path.substringAfterLast('/')
        val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else JBColor.foreground()
        val label = JBLabel(wrapHtml("&nbsp;&nbsp;&nbsp;&#x25B8;&nbsp;${escapeHtml(name)}")).apply {
            foreground = fg
            toolTipText = anchor.path
        }
        return clickableRow(label, isSelected, JBUI.Borders.empty(3, 10)) { controller.selectFile(sectionIndex, fileIndex) }
    }

    private fun clickableRow(label: JComponent, highlighted: Boolean, border: javax.swing.border.Border, action: () -> Unit): JComponent {
        val row = JPanel(BorderLayout()).apply {
            this.border = border
            isOpaque = highlighted
            if (highlighted) background = UIUtil.getListSelectionBackground(true)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            add(label, BorderLayout.CENTER)
        }
        installClick(row, action)
        if (!highlighted) installHover(row)
        row.maximumSize = Dimension(Int.MAX_VALUE, row.preferredSize.height)
        return row
    }

    /**
     * Make a whole row clickable. The listener goes on the row *and every child*, because Swing delivers a
     * mouse event to the deepest component that is itself a mouse target — and a label with a tooltip (the
     * file rows) becomes one, so the click never reaches the row otherwise. Fire on mousePressed so a pixel
     * of drag between press and release can't swallow it.
     */
    private fun installClick(row: JComponent, action: () -> Unit) {
        val adapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = action()
        }
        fun attach(c: Component) {
            c.addMouseListener(adapter)
            if (c is java.awt.Container) c.components.forEach { attach(it) }
        }
        attach(row)
    }

    private fun installHover(row: JPanel) {
        val adapter = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                row.isOpaque = true
                row.background = HOVER_BG
                row.repaint()
            }
            override fun mouseExited(e: MouseEvent) {
                val p = SwingUtilities.convertPoint(e.component, e.point, row)
                if (!row.contains(p)) {
                    row.isOpaque = false
                    row.repaint()
                }
            }
        }
        fun attach(c: Component) {
            c.addMouseListener(adapter)
            if (c is java.awt.Container) c.components.forEach { attach(it) }
        }
        attach(row)
    }

    /** Pick a trail JSON from disk and load it into the review. */
    private fun loadTrail() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").apply {
            title = "Load Trail"
            description = "Select a trail JSON file to review"
        }
        // Pre-select the currently-loaded trail (or the project root) so the picker opens somewhere useful.
        val current = DocentReviewService.getInstance(project).trailPath
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, project, current) ?: return
        controller.loadTrailFrom(chosen.path)
    }

    /**
     * The inline "connect the loaded trail to an agent" choices (no popup — everything renders in the rail): a
     * "start a new session" row per launchable provider, then each live workbench session. Picking a session pins
     * it as the push target and sends the resume hand-off ([linkTo]); picking a provider launches a fresh session
     * that resumes the trail itself ([startNewSession]). This is the UI half of resuming a review (the agent half
     * is docent_resume_review).
     */
    private fun buildConnectChoicesInto(target: JPanel) {
        val service = DocentReviewService.getInstance(project)
        newSessionRows { provider, label -> startNewSession(provider, label) }.forEach { target.add(it) }
        val sessions = service.sessionDirectory?.listSessions().orEmpty()
        if (sessions.isEmpty()) {
            target.add(cardMessageLabel(
                if (service.sessionLauncher != null) "No open agent sessions to connect — start a new one above."
                else "Agent Workbench isn't available. Open a workbench session, or resume from the agent with " +
                    "docent_resume_review.",
            ))
            return
        }
        // Reachable sessions connect directly; inactive ones are grouped under a message (their terminal isn't up),
        // and move into the active list on their own once their tab is activated (see [onEditorSelectionChanged]).
        val (active, inactive) = sessions.partition { it.reachable }
        active.forEach { target.add(connectSessionRow(it)) }
        if (inactive.isNotEmpty()) {
            target.add(sectionHeader("Not active yet"))
            target.add(cardMessageLabel("Click a session's tab in the editor to start it — it'll move up here once active."))
            inactive.forEach { target.add(connectSessionRow(it)) }
        }
    }

    /** A wrapping gray message for use *inside* the connect card — same [WrappingMessageArea] as [messageLabel],
     *  but fed the card's interior width via a provider, since its parent (the card's content panel) has no
     *  laid-out width at measure time (which is what made a plain [messageLabel] clip here). */
    private fun cardMessageLabel(text: String): JComponent =
        WrappingMessageArea(text) { cardContentWidth() }.apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6, 4)
            foreground = JBColor.GRAY
            font = UIUtil.getLabelFont()
            alignmentX = Component.LEFT_ALIGNMENT
        }

    /** The connect card's interior width (what its content panel gets once laid out): the live viewport width
     *  (excludes any scrollbar) minus the card's outer margin + border + padding, with a little slack. Falls back
     *  to the tracked rail width before the viewport is laid out. */
    private fun cardContentWidth(): Int {
        val vp = scrollPane.viewport.width
        val base = if (vp > JBUI.scale(80)) vp else contentWidth + JBUI.scale(24)
        return (base - JBUI.scale(40)).coerceAtLeast(JBUI.scale(120))
    }

    /** A live-session row in the connect list; clicking connects the Docent to it (or, if inactive, guides the
     *  reviewer to activate its tab first). Inactive rows are dimmed and grouped by the caller. */
    private fun connectSessionRow(s: AgentSessionInfo): JComponent {
        val title = escapeHtml(cropTitle(s.title))
        val age = relativeTime(s.updatedAt)
        val suffix = if (age.isEmpty()) "" else "&nbsp;&nbsp;<font color=\"#808080\">·&nbsp;${escapeHtml(age)}</font>"
        val label = JBLabel(wrapHtml("&#x25B8;&nbsp;&nbsp;$title$suffix")).apply {
            if (!s.reachable) foreground = JBColor.GRAY
            toolTipText = if (s.reachable) "Connect the Docent to this session"
            else "Open this session's tab to activate it, then connect"
        }
        return clickableRow(label, false, JBUI.Borders.empty(4, 10)) { linkTo(s) }
    }

    /** A "✦ Start a new <provider> session" row per launchable provider (empty when the workbench can't launch).
     *  [onPick] gets the provider value + label. */
    private fun newSessionRows(onPick: (String, String) -> Unit): List<JComponent> {
        if (DocentReviewService.getInstance(project).sessionLauncher == null) return emptyList()
        return NEW_SESSION_PROVIDERS.map { (value, label) ->
            val row = JBLabel(wrapHtml("&#x2726;&nbsp;&nbsp;Start a new $label session"))
            clickableRow(row, false, JBUI.Borders.empty(4, 10)) { onPick(value, label) }
        }
    }

    /** Ask [picked] to finalize and open the review now (clicked from the no-trail surface). No review is armed
     *  yet, so we set the push-target fields the notifier reads directly; the agent's docent_finalize_trail
     *  re-links authoritatively via its sessionToken. The empty state then shows a "preparing…" line until the
     *  trail loads here. */
    private fun sendStartReview(picked: AgentSessionInfo) {
        val service = DocentReviewService.getInstance(project)
        // A cold chat tab can't be reached now (see [showActivateFirst]); guide the reviewer instead of no-op'ing.
        if (!picked.reachable) return showActivateFirst(picked)
        linkedTitle = picked.title
        service.agentProvider = picked.provider
        service.deliveryMode = deliveryModeForProvider(picked.provider)
        service.agentThreadId = picked.threadId
        val pushed = service.eventNotifier?.notifyAgent(
            ReviewEvent(id = "", kind = DocentReviewService.START_REVIEW),
        ) ?: false
        if (pushed) {
            awaitingStartFrom = cropTitle(picked.title.ifBlank { "the agent" })
            refreshList()
        } else {
            Messages.showInfoMessage(
                project,
                "Couldn't message that session. Ask the agent in chat to call docent_finalize_trail to start the review.",
                "Start Review",
            )
        }
    }

    /**
     * Launch a brand-new agent session and ask it to build the review from the recorded decisions (clicked from
     * the no-trail surface's alternatives). The reliable path when none of the live sessions is the author — a
     * fresh session can still call docent_finalize_trail, which consumes the project-wide decision log. [anchor]
     * is the link the provider chooser opens above.
     */
    /** Launch a fresh agent session (of [provider]) that builds the review from the recorded decisions — the
     *  no-trail "start a new session" path. A fresh session can still call docent_finalize_trail, which consumes
     *  the project-wide decision log. */
    private fun startFreshSessionWith(provider: String, label: String) {
        val launcher = DocentReviewService.getInstance(project).sessionLauncher ?: run {
            Messages.showInfoMessage(
                project,
                "Agent Workbench isn't available. Start a session there, then it'll appear here to review.",
                "Start Review",
            )
            return
        }
        val prompt = "Please start the Code Review Docent review: call docent_finalize_trail now to synthesize the " +
            "trail from the recorded decisions and open the walkthrough in the review UI."
        if (launcher.startSession(prompt, provider)) {
            awaitingStartFrom = "a new $label session"
            refreshList()
        } else {
            Messages.showInfoMessage(
                project,
                "Couldn't start a new $label session. Start one from the Agent Workbench instead.",
                "Start Review",
            )
        }
    }

    /** Launch a fresh agent session and tell it to resume on the loaded trail (it arms the review itself via
     *  docent_resume_review). The reliable path when there's no existing session to connect to. */
    private fun startNewSession(provider: String, label: String) {
        val service = DocentReviewService.getInstance(project)
        val path = service.trailPath
        val launcher = service.sessionLauncher
        if (path == null || launcher == null) {
            Messages.showInfoMessage(project, "Can't start a new agent session right now.", "Connect Agent")
            return
        }
        val prompt = "Please resume the Code Review Docent review for the trail at `$path`. " +
            "Call docent_resume_review(path=\"$path\") now to load it into the review UI and connect yourself as " +
            "the Docent, then read the trail file to refresh the WHY before answering the reviewer."
        if (!launcher.startSession(prompt, provider)) {
            Messages.showInfoMessage(
                project,
                "Couldn't start a new $label session. Start one from the Agent Workbench, then use Connect agent again.",
                "Connect Agent",
            )
            return
        }
        // The launched agent calls docent_resume_review, which arms the review (reviewActive) and pins itself as
        // the push target via its sessionToken. The launch contributor sets agentProvider + deliveryMode for this
        // provider as it launches. The status flips when the resume lands (onConnectionChanged).
        refreshList()
    }

    /** Pin [picked] as the review's agent and tell it to resume on the loaded trail. */
    private fun linkTo(picked: AgentSessionInfo) {
        val service = DocentReviewService.getInstance(project)
        linkedTitle = picked.title
        // Authoritative provider/delivery for the picked session (the launch contributor's per-launch guess can be
        // stale if other sessions launched since): Claude → Monitor (watch the EventLog), Codex → await (block).
        service.agentProvider = picked.provider
        service.deliveryMode = deliveryModeForProvider(picked.provider)
        // A cold chat tab (never opened this IDE run) has no live terminal and we won't type into a booting one,
        // so it's unreachable now — don't arm a dead connection; tell the reviewer to activate it first.
        if (!picked.reachable) return showActivateFirst(picked)
        service.linkAgentSession(picked.threadId) // arms the loop + begins the event log the resume prompt references
        // The resume push is the ONLY way to wake an idle session and tell it it's the Docent — unlike ongoing
        // actions, there's no watched log for it to catch up on. If it doesn't land, the agent isn't listening:
        // roll the arm back rather than show a false "connected".
        val delivered = service.eventNotifier?.notifyAgent(
            ReviewEvent(
                id = "",
                kind = DocentReviewService.REVIEW_RESUMED,
                file = service.trailPath ?: "",
                text = controller.trail?.subject ?: "",
            ),
        ) ?: false
        if (delivered) showConnectChoices = false // collapse the picker; we're connected now
        else {
            service.reset() // clears reviewActive + the just-begun event log; the trail stays loaded
            linkedTitle = null
            showActivateFirst(picked)
        }
        refreshList()
    }

    /** The picked session can't be reached right now (a chat tab that hasn't been activated this IDE run). Tell
     *  the reviewer to activate it first — we deliberately don't type into a still-booting terminal. */
    private fun showActivateFirst(picked: AgentSessionInfo) {
        linkedTitle = null
        Messages.showInfoMessage(
            project,
            "“${cropTitle(picked.title)}” isn't active yet. Click its tab in the editor to open the session " +
                "(its terminal starts then), and try connecting again — or ask that agent to run docent_resume_review.",
            "Activate the Session First",
        )
        refreshList()
    }

    private fun relativeTime(epochMillis: Long): String {
        if (epochMillis <= 0) return ""
        val secs = (System.currentTimeMillis() - epochMillis) / 1000
        return when {
            secs < 0 -> ""
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60}m ago"
            secs < 86_400 -> "${secs / 3600}h ago"
            else -> "${secs / 86_400}d ago"
        }
    }

    private fun completeReview() {
        val service = DocentReviewService.getInstance(project)
        val n = service.queuedChanges().size
        val message =
            if (n == 0) "Complete the review? The Docent will wrap up — no changes are queued."
            else "Complete the review? The Docent will now implement $n queued change${if (n == 1) "" else "s"}."
        if (Messages.showYesNoDialog(project, message, "Complete Review", Messages.getQuestionIcon()) == Messages.YES) {
            // With queued changes, note that the agent is implementing them; with none, just fall back to the
            // clean "no pending changes" initial state (nothing is pending, so show nothing extra).
            completionNote = if (n == 0) null
                else "✔  Review complete — the agent is implementing $n requested change${if (n == 1) "" else "s"}. " +
                    "A new review will appear here when it's ready."
            service.completeReview()
            controller.endReview() // drop the trail → the nav returns to the start-review surface
        }
    }

    private fun decisions(n: Int): String = "decision${if (n == 1) "" else "s"}"
    private fun changes(n: Int): String = "change${if (n == 1) "" else "s"}"

    private fun escapeHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Crop a session title for display with a **middle** ellipsis (like the workbench chat tabs), keeping the
     *  head and tail that disambiguate — long agent-authored titles are otherwise unreadable in the rail, the
     *  picker, and dialogs. Blank → a placeholder. Apply before [escapeHtml] for HTML labels. */
    private fun cropTitle(s: String, max: Int = 40): String {
        val t = s.ifBlank { "(untitled session)" }
        if (t.length <= max) return t
        val head = (max - 1) / 2
        val tail = max - 1 - head
        return t.take(head).trimEnd() + "…" + t.takeLast(tail).trimStart()
    }

    override fun dispose() {
        controller.removeListener(this)
        DocentReviewService.getInstance(project).onChangesUpdated = null
        DecisionLog.getInstance(project).onUpdated = null
    }

    private companion object {
        private val CONNECTED = JBColor(Color(0x4C9A4E), Color(0x62B266))
        private val HOVER_BG = JBColor(Color(0xEDF4FE), Color(0x2E3034))

        /** Border of the railroaded "Start reviewing" card — the green that reads as "go". */
        private val START_BORDER = JBColor(Color(0x4C9A4E), Color(0x62B266))

        /** Providers the UI offers a "start a new session" entry for (value → display label). Matches the
         *  providers the Docent can drive (Claude, Codex); see WorkbenchSessionDirectory.SUPPORTED_PROVIDERS. */
        private val NEW_SESSION_PROVIDERS = listOf("claude" to "Claude", "codex" to "Codex")
    }
}
