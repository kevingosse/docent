package com.kevingosse.docent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.trail.Anchor
import com.kevingosse.docent.trail.Section
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
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

    init {
        add(subject, BorderLayout.NORTH)
        add(JBScrollPane(planList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(
            JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Load trail…").apply { addActionListener { loadTrail() } })
                add(JButton("Complete review").apply { addActionListener { completeReview() } })
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

    private fun rebuild() {
        val j = controller.trail
        subject.text = when {
            j != null -> "<html><b>${escapeHtml(j.subject)}</b></html>"
            else -> "<html><b>Code Review Docent</b></html>"
        }
        refreshList()
        updateQueueLabel()
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

    private fun completeReview() {
        val service = DocentReviewService.getInstance(project)
        val n = service.queuedChanges().size
        val message =
            if (n == 0) "Complete the review? The Docent will wrap up — no changes are queued."
            else "Complete the review? The Docent will now implement $n queued change${if (n == 1) "" else "s"}."
        if (Messages.showYesNoDialog(project, message, "Complete Review", Messages.getQuestionIcon()) == Messages.YES) {
            service.completeReview()
            updateQueueLabel()
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
}
