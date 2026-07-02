package com.kevingosse.docent.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.EmptyIcon
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
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable

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

    /** Hosts the trail subject as a [DocentUi.WrappingHtmlPane] (wrap width = live panel width, so it
     *  re-wraps on every layout instead of clipping to a stale cached width). */
    private val subjectHost = object : JPanel(BorderLayout()) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(10, 10, 4, 10)
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
    }

    /** Progress echo under the subject: the segmented trail strip + "n of m read" (docs/UI.md §7). */
    private val progressRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 10, 8, 10)
        alignmentX = Component.LEFT_ALIGNMENT
        isVisible = false
    }

    private val header = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(subjectHost)
        add(progressRow)
    }

    /** A one-shot inline warning (a failed push/launch) shown at the top of the rail instead of a modal
     *  dialog — the rail promised "everything inline"; the error paths honor it too (docs/UI.md §8). */
    private var notice: String? = null
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
        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        // Every wrapping component reads its width from a live provider at layout time (DocentUi.WrappingText /
        // WrappingHtmlPane), so a resize only needs a re-layout — no rebuild, no cached width to go stale.
        planList.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                planList.revalidate()
                planList.repaint()
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
        subjectHost.removeAll()
        subjectHost.isVisible = j != null
        if (j != null) {
            subjectHost.add(
                DocentUi.WrappingHtmlPane("<b>${escapeHtml(j.subject)}</b>") { width - JBUI.scale(24) },
                BorderLayout.CENTER,
            )
        }
        subjectHost.revalidate()
        refreshList()
    }

    private fun refreshList() {
        planList.removeAll()
        val j = controller.trail
        updateProgressRow(j)
        notice?.let { planList.add(noticeRow(it)) }
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

    /** Rebuild the header's progress echo: hidden with no trail, else the strip + "n of m read". */
    private fun updateProgressRow(j: com.kevingosse.docent.trail.Trail?) {
        progressRow.removeAll()
        val sections = j?.sections.orEmpty()
        progressRow.isVisible = sections.isNotEmpty()
        if (sections.isEmpty()) { progressRow.revalidate(); progressRow.repaint(); return }
        val visited = controller.visited.filterTo(mutableSetOf()) { it >= 0 }
        val current = if (controller.reviewTabOpen) controller.currentSectionIndex else -1
        progressRow.add(DocentUi.SegmentedProgress(sections.size, visited, current))
        progressRow.add(JBLabel("${visited.size} of ${sections.size} read").apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
        })
        progressRow.revalidate()
        progressRow.repaint()
    }

    /** An inline warning row (replaces the old modal info dialogs). Cleared on the next action. */
    private fun noticeRow(text: String): JComponent = object : JPanel(BorderLayout(JBUI.scale(6), 0)) {
        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10, 4, 10)
        alignmentX = Component.LEFT_ALIGNMENT
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(JBLabel(AllIcons.General.BalloonWarning), BorderLayout.NORTH)
            },
            BorderLayout.WEST,
        )
        add(DocentUi.WrappingText(text), BorderLayout.CENTER)
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
        val card = DocentUi.RoundedPanel(null).apply {
            border = DocentUi.cardBorder(if (connected) JBColor.border() else DocentUi.GO)
            alignmentX = Component.LEFT_ALIGNMENT
            add(content, BorderLayout.CENTER)
        }
        if (showConnectChoices) {
            content.add(connectHeaderRow(title))
            buildConnectChoicesInto(content)
        } else {
            // Reserve: rail margins (16) + card border/padding (22) + icon gap (6) + slack.
            content.add(iconTextRow(AllIcons.Actions.Execute, "<b>${escapeHtml(title)}</b>", unscaledReserve = 56))
            card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            card.toolTipText = "Connect an agent to this trail"
            DocentUi.installClick(card) { showConnectChoices = true; refreshList() }
            DocentUi.installHover(card)
        }
        // Max height must track the *laid-out* preferred height: the expanded card holds wrapping message labels
        // whose height isn't known until they have a width, so a fixed max (computed at build time) clips them.
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 8)
            add(card, BorderLayout.CENTER)
        }
    }

    /** The expanded connect card's header: bold title on the left, a close ✕ on the right that collapses it. */
    private fun connectHeaderRow(title: String): JComponent {
        val close = JBLabel(AllIcons.Actions.CloseHovered).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Close"
            border = JBUI.Borders.emptyLeft(8)
        }
        DocentUi.installClick(close) { showConnectChoices = false; refreshList() }
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(6)
            add(
                DocentUi.WrappingHtmlPane("<b>${escapeHtml(title)}</b>") { cardContentWidth() - JBUI.scale(40) },
                BorderLayout.CENTER,
            )
            add(close, BorderLayout.EAST)
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
        // Reserve: rail margins (16) + card border/padding (22) + icon gap (6) + slack.
        val titleLabel = iconTextRow(icon, "<b>${escapeHtml(title)}</b>", unscaledReserve = 56)
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
        val card = DocentUi.RoundedPanel(null).apply {
            border = DocentUi.cardBorder(DocentUi.GO)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            add(content, BorderLayout.CENTER)
            if (tooltip != null) toolTipText = tooltip
        }
        DocentUi.installClick(card) { action() }
        DocentUi.installHover(card)
        return object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 8)
            add(card, BorderLayout.CENTER)
        }
    }

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
        val label = iconTextRow(
            null, "&#x25B8;&nbsp;&nbsp;$title$suffix", unscaledReserve = 28,
            tooltip = if (s.reachable) "Start the Docent review on this session"
            else "Open this session's tab to activate it, then start the review",
        )
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
            foreground = if (connected) DocentUi.GO else JBColor.GRAY
            border = JBUI.Borders.empty(2, 10, 2, 10)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    /** A wrapped, gray prose line for the no-trail surface (instructions / status). */
    private fun messageLabel(text: String): JComponent =
        DocentUi.WrappingText(text).apply {
            border = JBUI.Borders.empty(8, 10)
            foreground = JBColor.GRAY
        }

    private fun sectionHeader(text: String): JComponent =
        JBLabel(text.uppercase()).apply {
            font = JBUI.Fonts.smallFont()
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(12, 10, 4, 10)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    /**
     * An optional icon beside wrapping HTML text — the standard content of a rail row. The text's wrap width
     * comes from [widthOf] at every layout pass (live, never cached); [unscaledReserve] is the horizontal
     * space the row itself consumes around the text (borders + gaps + slack), in unscaled units. The icon
     * sits top-anchored so multi-line text hangs below its first line, not around a centered icon.
     */
    private fun iconTextRow(
        icon: Icon?,
        html: String,
        foreground: Color? = null,
        unscaledReserve: Int,
        tooltip: String? = null,
        widthOf: () -> Int = { planList.width },
    ): JComponent {
        val pane = DocentUi.WrappingHtmlPane(html, foreground) {
            widthOf() - JBUI.scale(unscaledReserve) - (icon?.iconWidth ?: 0)
        }
        if (tooltip != null) pane.toolTipText = tooltip
        pane.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) // rows are clickable; no text I-beam
        return object : JPanel(BorderLayout(JBUI.scale(6), 0)) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            if (tooltip != null) toolTipText = tooltip
            if (icon != null) {
                add(
                    JPanel(BorderLayout()).apply {
                        isOpaque = false
                        add(JBLabel(icon), BorderLayout.NORTH)
                    },
                    BorderLayout.WEST,
                )
            }
            add(pane, BorderLayout.CENTER)
        }
    }

    private fun overviewRow(): JComponent {
        val isCurrent = controller.reviewTabOpen && controller.currentSectionIndex < 0
        val fg = if (isCurrent) UIUtil.getListSelectionForeground(true) else null
        val label = iconTextRow(null, "<b>Overview</b>", fg, unscaledReserve = 24)
        return clickableRow(label, isCurrent, JBUI.Borders.empty(6, 8)) { controller.showOverview() }
    }

    private fun planRow(index: Int, section: Section): JComponent {
        // Highlight the section row when showing its summary; a selected file highlights its own row instead.
        // Only when the review tab is actually open (a closed middle pane shows no selection).
        val isCurrent = controller.reviewTabOpen &&
            index == controller.currentSectionIndex && controller.currentFileIndex < 0
        val fg = if (isCurrent) UIUtil.getListSelectionForeground(true) else null
        val label = iconTextRow(
            if (controller.visited.contains(index)) AllIcons.Actions.Commit else EmptyIcon.ICON_16,
            "<b>${index + 1}.</b>&nbsp;${escapeHtml(section.headline)}",
            fg,
            unscaledReserve = 32, // row borders (16) + icon gap (6) + slack
        )
        return clickableRow(label, isCurrent, JBUI.Borders.empty(6, 8)) { controller.selectSection(index) }
    }

    private fun fileRow(sectionIndex: Int, fileIndex: Int, anchor: Anchor): JComponent {
        val isSelected = controller.reviewTabOpen &&
            sectionIndex == controller.currentSectionIndex && fileIndex == controller.currentFileIndex
        val name = anchor.path.substringAfterLast('/')
        val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else null
        val comments = anchor.comments?.size ?: 0
        val suffix = if (comments == 0) "" else
            "&nbsp;&nbsp;<font color=\"#808080\">· $comments comment${if (comments == 1) "" else "s"}</font>"
        val label = iconTextRow(
            FileTypeManager.getInstance().getFileTypeByFileName(name).icon,
            "${escapeHtml(name)}$suffix",
            fg,
            unscaledReserve = 48, // row borders (34) + icon gap (6) + slack
            tooltip = anchor.path,
        )
        return clickableRow(label, isSelected, JBUI.Borders.empty(3, 24, 3, 10)) { controller.selectFile(sectionIndex, fileIndex) }
    }

    private fun clickableRow(label: JComponent, highlighted: Boolean, border: javax.swing.border.Border, action: () -> Unit): JComponent {
        // Max height tracks the *live* preferred height (wrapping text re-measures on resize) — a height
        // frozen at build time is exactly the clipping bug the wrapping components exist to prevent.
        val row = object : JPanel(BorderLayout()) {
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            this.border = border
            isOpaque = highlighted
            if (highlighted) background = UIUtil.getListSelectionBackground(true)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            add(label, BorderLayout.CENTER)
        }
        DocentUi.installClick(row, action)
        if (!highlighted) DocentUi.installHover(row)
        return row
    }

    /** Pick a trail JSON from disk and load it into the review. */
    private fun loadTrail() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("json").apply {
            title = "Load Trail"
            description = "Select a trail JSON file to review"
        }
        // Pre-select the currently-loaded trail, else open in .idea/docent/ (where trails are written).
        val fs = LocalFileSystem.getInstance()
        val current = DocentReviewService.getInstance(project).trailPath?.let { fs.findFileByPath(it) }
            ?: project.basePath?.let { fs.refreshAndFindFileByPath("$it/.idea/docent") }
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
        newSessionRows(widthOf = { cardContentWidth() }) { provider, label -> startNewSession(provider, label) }
            .forEach { target.add(it) }
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

    /** A wrapping gray message for use *inside* the connect card — same [DocentUi.WrappingText] as
     *  [messageLabel], but fed the card's interior width via a provider, since its parent (the card's content
     *  panel) has no laid-out width at measure time (which is what made a plain [messageLabel] clip here). */
    private fun cardMessageLabel(text: String): JComponent =
        DocentUi.WrappingText(text) { cardContentWidth() }.apply {
            border = JBUI.Borders.empty(6, 4)
            foreground = JBColor.GRAY
        }

    /** The connect card's interior width (what its content panel gets once laid out): the live viewport width
     *  (excludes any scrollbar) minus the card's outer margin + border + padding, with a little slack. Falls back
     *  to the tracked rail width before the viewport is laid out. */
    private fun cardContentWidth(): Int {
        val vp = scrollPane.viewport.width
        val base = if (vp > JBUI.scale(80)) vp else planList.width
        return (base - JBUI.scale(40)).coerceAtLeast(JBUI.scale(120))
    }

    /** A live-session row in the connect list; clicking connects the Docent to it (or, if inactive, guides the
     *  reviewer to activate its tab first). Inactive rows are dimmed and grouped by the caller. */
    private fun connectSessionRow(s: AgentSessionInfo): JComponent {
        val title = escapeHtml(cropTitle(s.title))
        val age = relativeTime(s.updatedAt)
        val suffix = if (age.isEmpty()) "" else "&nbsp;&nbsp;<font color=\"#808080\">·&nbsp;${escapeHtml(age)}</font>"
        val label = iconTextRow(
            null, "&#x25B8;&nbsp;&nbsp;$title$suffix",
            foreground = if (s.reachable) null else JBColor.GRAY,
            unscaledReserve = 28,
            tooltip = if (s.reachable) "Connect the Docent to this session"
            else "Open this session's tab to activate it, then connect",
            widthOf = { cardContentWidth() }, // these rows live inside the connect card, not the bare rail
        )
        return clickableRow(label, false, JBUI.Borders.empty(4, 10)) { linkTo(s) }
    }

    /** A "✦ Start a new <provider> session" row per launchable provider (empty when the workbench can't launch).
     *  [onPick] gets the provider value + label; [widthOf] is the host surface's live width (the rail by
     *  default; the connect card's interior when nested there). */
    private fun newSessionRows(widthOf: () -> Int = { planList.width }, onPick: (String, String) -> Unit): List<JComponent> {
        if (DocentReviewService.getInstance(project).sessionLauncher == null) return emptyList()
        return NEW_SESSION_PROVIDERS.map { (value, label) ->
            val row = iconTextRow(null, "&#x2726;&nbsp;&nbsp;Start a new $label session", unscaledReserve = 28, widthOf = widthOf)
            clickableRow(row, false, JBUI.Borders.empty(4, 10)) { onPick(value, label) }
        }
    }

    /** Ask [picked] to finalize and open the review now (clicked from the no-trail surface). No review is armed
     *  yet, so we set the push-target fields the notifier reads directly; the agent's docent_finalize_trail
     *  re-links authoritatively via its sessionToken. The empty state then shows a "preparing…" line until the
     *  trail loads here. */
    private fun sendStartReview(picked: AgentSessionInfo) {
        val service = DocentReviewService.getInstance(project)
        notice = null
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
        } else {
            notice = "Couldn't message that session. Ask the agent in chat to call docent_finalize_trail to start the review."
        }
        refreshList()
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
        notice = null
        val launcher = DocentReviewService.getInstance(project).sessionLauncher ?: run {
            notice = "Agent Workbench isn't available. Start a session there, then it'll appear here to review."
            refreshList()
            return
        }
        val prompt = "Please start the Code Review Docent review: call docent_finalize_trail now to synthesize the " +
            "trail from the recorded decisions and open the walkthrough in the review UI."
        if (launcher.startSession(prompt, provider)) {
            awaitingStartFrom = "a new $label session"
        } else {
            notice = "Couldn't start a new $label session. Start one from the Agent Workbench instead."
        }
        refreshList()
    }

    /** Launch a fresh agent session and tell it to resume on the loaded trail (it arms the review itself via
     *  docent_resume_review). The reliable path when there's no existing session to connect to. */
    private fun startNewSession(provider: String, label: String) {
        val service = DocentReviewService.getInstance(project)
        notice = null
        val path = service.trailPath
        val launcher = service.sessionLauncher
        if (path == null || launcher == null) {
            notice = "Can't start a new agent session right now."
            refreshList()
            return
        }
        val prompt = "Please resume the Code Review Docent review for the trail at `$path`. " +
            "Call docent_resume_review(path=\"$path\") now to load it into the review UI and connect yourself as " +
            "the Docent, then read the trail file to refresh the WHY before answering the reviewer."
        if (!launcher.startSession(prompt, provider)) {
            notice = "Couldn't start a new $label session. Start one from the Agent Workbench, then use Connect agent again."
            refreshList()
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
        notice = "“${cropTitle(picked.title)}” isn't active yet. Click its tab in the editor to open the session " +
            "(its terminal starts then), and try connecting again — or ask that agent to run docent_resume_review."
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
        /** Providers the UI offers a "start a new session" entry for (value → display label). Matches the
         *  providers the Docent can drive (Claude, Codex); see WorkbenchSessionDirectory.SUPPORTED_PROVIDERS. */
        private val NEW_SESSION_PROVIDERS = listOf("claude" to "Claude", "codex" to "Codex")
    }
}
