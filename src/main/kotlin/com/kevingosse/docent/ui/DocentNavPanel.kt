package com.kevingosse.docent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kevingosse.docent.AgentSessionInfo
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.ReviewEvent
import com.kevingosse.docent.trail.Anchor
import com.kevingosse.docent.trail.Section
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The navigation rail, hosted in the left "Docent" tool window (see [DocentToolWindowFactory]). Lists the
 * sections and, under the current section, the files it touches. Selecting either drives the review tab in the
 * editor pane via [DocentReviewController] — this panel only writes selection and reflects it.
 */
class DocentNavPanel(private val project: Project) : JPanel(BorderLayout()), Disposable, DocentReviewController.Listener {

    private val controller = DocentReviewController.getInstance(project)

    private val subject = JBLabel("").apply { border = JBUI.Borders.empty(10, 10, 6, 10) }
    private val planList = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
    private val queueLabel = JBLabel("").apply { foreground = JBColor.GRAY }
    private val connectButton = JButton("Connect agent…").apply { addActionListener { connectAgent() } }
    private val connectionLabel = JBLabel("").apply { foreground = JBColor.GRAY }

    /** The title of the session linked via the UI picker, for the status label (null when linked via the MCP
     *  handoff — we don't know its title then — or when not connected). */
    private var linkedTitle: String? = null

    init {
        add(subject, BorderLayout.NORTH)
        add(JBScrollPane(planList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Load trail…").apply { addActionListener { loadTrail() } })
                add(connectButton)
                add(JButton("Complete review").apply { addActionListener { completeReview() } })
                add(connectionLabel)
                add(queueLabel)
            },
            BorderLayout.SOUTH,
        )

        controller.addListener(this)
        controller.ensureLoaded()
        DocentReviewService.getInstance(project).onChangesUpdated = {
            ApplicationManager.getApplication().invokeLater({ updateQueueLabel() }, ModalityState.any())
        }
        rebuild()
    }

    override fun onModelChanged() = rebuild()
    override fun onSelectionChanged() = refreshList()
    override fun onConnectionChanged() = updateConnectionState()

    private fun rebuild() {
        val j = controller.trail
        subject.text = when {
            j != null -> "<html><b>${escapeHtml(j.subject)}</b></html>"
            else -> "<html><b>Code Review Docent</b></html>"
        }
        refreshList()
        updateQueueLabel()
        updateConnectionState()
    }

    private fun refreshList() {
        planList.removeAll()
        val j = controller.trail
        if (j == null) {
            planList.add(
                JBLabel(
                    "<html>" + escapeHtml(
                        controller.loadError?.let { "Couldn't load the trail:\n$it" }
                            ?: "No trail loaded.",
                    ).replace("\n", "<br>") + "</html>",
                ).apply { border = JBUI.Borders.empty(10); foreground = JBColor.GRAY },
            )
        } else {
            planList.add(overviewRow())
            j.sections.forEachIndexed { i, section ->
                planList.add(planRow(i, section))
                // Expand a section's files only while its review is actually open in the editor.
                if (controller.reviewTabOpen && i == controller.currentSectionIndex) {
                    section.anchors.forEachIndexed { fi, anchor -> planList.add(fileRow(i, fi, anchor)) }
                }
            }
        }
        planList.revalidate()
        planList.repaint()
    }

    private fun overviewRow(): JComponent {
        val isCurrent = controller.reviewTabOpen && controller.currentSectionIndex < 0
        val fg = if (isCurrent) UIUtil.getListSelectionForeground(true) else JBColor.foreground()
        val label = JBLabel("<html><b>Overview</b></html>").apply { foreground = fg }
        return clickableRow(label, isCurrent, JBUI.Borders.empty(6, 8)) { controller.showOverview() }
    }

    private fun planRow(index: Int, section: Section): JComponent {
        // Highlight the section row when showing its summary; a selected file highlights its own row instead.
        // Only when the review tab is actually open (a closed middle pane shows no selection).
        val isCurrent = controller.reviewTabOpen &&
            index == controller.currentSectionIndex && controller.currentFileIndex < 0
        val glyph = if (controller.visited.contains(index)) "&#x2713;" else "&#x25CB;" // ✓ / ○
        val fg = if (isCurrent) UIUtil.getListSelectionForeground(true) else JBColor.foreground()
        val label = JBLabel(
            "<html>$glyph&nbsp;&nbsp;<b>${index + 1}.</b>&nbsp;${escapeHtml(section.headline)}</html>"
        ).apply { foreground = fg }
        return clickableRow(label, isCurrent, JBUI.Borders.empty(6, 8)) { controller.selectSection(index) }
    }

    private fun fileRow(sectionIndex: Int, fileIndex: Int, anchor: Anchor): JComponent {
        val isSelected = controller.reviewTabOpen &&
            sectionIndex == controller.currentSectionIndex && fileIndex == controller.currentFileIndex
        val name = anchor.path.substringAfterLast('/')
        val fg = if (isSelected) UIUtil.getListSelectionForeground(true) else JBColor.foreground()
        val label = JBLabel("<html>&nbsp;&nbsp;&nbsp;&#x25B8;&nbsp;${escapeHtml(name)}</html>").apply {
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
     * Connect the loaded trail to a live agent: list the workbench's active Claude sessions and let the user pick
     * one. Linking pins it as the push target ([DocentReviewService.linkAgentSession]) and sends it a "resume"
     * message so it reads the trail and takes over as the Docent. This is the UI half of resuming a review (the
     * agent half is the docent_resume_review MCP tool).
     */
    private fun connectAgent() {
        val service = DocentReviewService.getInstance(project)
        if (controller.trail == null) {
            Messages.showInfoMessage(project, "Load a trail first, then connect an agent to it.", "Connect Agent")
            return
        }
        val canLaunch = service.sessionLauncher != null
        val sessions = service.sessionDirectory?.listSessions().orEmpty()
        if (!canLaunch && sessions.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "Agent Workbench isn't available. Start a workbench Claude session, or resume from the agent side " +
                    "with the docent_resume_review tool.",
                "Connect Agent",
            )
            return
        }
        // "Start a new agent session" first (the reliable path for a not-yet-started session — which has no id to
        // target), then the existing started sessions.
        val choices = buildList {
            if (canLaunch) add(ConnectChoice.StartNew)
            sessions.forEach { add(ConnectChoice.Existing(it)) }
        }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle("Connect Agent")
            .setRenderer(@Suppress("DEPRECATION") SimpleListCellRenderer.create("") { choiceLabel(it) })
            .setNamerForFiltering { choiceFilter(it) } // type to filter
            .setVisibleRowCount(12) // cap height so the popup doesn't fill the screen
            .setItemChosenCallback { onChoice(it) }
            .createPopup()
        // The button sits at the bottom of the tool window, so show the popup ABOVE it rather than below.
        val height = popup.content.preferredSize.height
        popup.show(RelativePoint(connectButton, Point(0, -height)))
    }

    private fun choiceLabel(c: ConnectChoice): String = when (c) {
        ConnectChoice.StartNew -> "✦  Start a new agent session"
        is ConnectChoice.Existing -> sessionLabel(c.info)
    }

    private fun choiceFilter(c: ConnectChoice): String = when (c) {
        ConnectChoice.StartNew -> "start new agent session"
        is ConnectChoice.Existing -> c.info.title
    }

    private fun onChoice(c: ConnectChoice) {
        when (c) {
            ConnectChoice.StartNew -> startNewSession()
            is ConnectChoice.Existing -> linkTo(c.info)
        }
    }

    /** Launch a fresh agent session and tell it to resume on the loaded trail (it arms the review itself via
     *  docent_resume_review). The reliable path when there's no existing session to connect to. */
    private fun startNewSession() {
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
        if (!launcher.startSession(prompt)) {
            Messages.showInfoMessage(
                project,
                "Couldn't start a new agent session. Start one from the Agent Workbench, then use Connect agent again.",
                "Connect Agent",
            )
            return
        }
        // The launched agent calls docent_resume_review, which arms the review (reviewActive) and pins itself as
        // the push target via its sessionToken. The status flips when that lands (onConnectionChanged).
        connectionLabel.text = "  ⟳ Starting a new agent session…"
        connectionLabel.foreground = JBColor.GRAY
    }

    /** Pin [picked] as the review's agent and tell it to resume on the loaded trail. */
    private fun linkTo(picked: AgentSessionInfo) {
        val service = DocentReviewService.getInstance(project)
        linkedTitle = picked.title
        service.linkAgentSession(picked.threadId)
        // Hand the agent its role + the trail to read. Best-effort: a missing/failed notifier just means the
        // agent must catch up via docent_await_event; the review is armed either way.
        service.eventNotifier?.notifyAgent(
            ReviewEvent(
                id = "",
                kind = DocentReviewService.REVIEW_RESUMED,
                file = service.trailPath ?: "",
                text = controller.trail?.subject ?: "",
            ),
        )
        updateConnectionState()
    }

    private fun sessionLabel(s: AgentSessionInfo): String {
        val title = s.title.ifBlank { "(untitled session)" }.let { if (it.length > 60) it.take(57) + "…" else it }
        val age = relativeTime(s.updatedAt)
        return if (age.isEmpty()) title else "$title   ·   $age"
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

    private fun updateConnectionState() {
        val service = DocentReviewService.getInstance(project)
        val hasTrail = controller.trail != null
        connectButton.isEnabled = hasTrail
        if (!service.reviewActive) linkedTitle = null
        connectionLabel.text = when {
            service.reviewActive -> "  ● ${linkedTitle?.let { "Connected: $it" } ?: "Agent connected"}"
            hasTrail -> "  ○ Not connected"
            else -> ""
        }
        connectionLabel.foreground = if (service.reviewActive) CONNECTED else JBColor.GRAY
    }

    private fun completeReview() {
        val service = DocentReviewService.getInstance(project)
        val n = service.queuedChanges().size
        val message =
            if (n == 0) "Complete the review? The Docent will wrap up — no changes are queued."
            else "Complete the review? The Docent will now implement $n queued change${if (n == 1) "" else "s"}."
        if (Messages.showYesNoDialog(project, message, "Complete Review", Messages.getQuestionIcon()) == Messages.YES) {
            service.completeReview()
            updateQueueLabel()
            controller.closeReview()
        }
    }

    private fun updateQueueLabel() {
        val n = DocentReviewService.getInstance(project).queuedChanges().size
        queueLabel.text = if (n == 0) "" else "  $n change${if (n == 1) "" else "s"} queued"
    }

    private fun escapeHtml(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    override fun dispose() {
        controller.removeListener(this)
        DocentReviewService.getInstance(project).onChangesUpdated = null
    }

    /** An entry in the "Connect agent" picker: start a fresh session, or connect an existing one. */
    private sealed interface ConnectChoice {
        object StartNew : ConnectChoice
        data class Existing(val info: AgentSessionInfo) : ConnectChoice
    }

    private companion object {
        private val CONNECTED = JBColor(Color(0x4C9A4E), Color(0x62B266))
    }
}
