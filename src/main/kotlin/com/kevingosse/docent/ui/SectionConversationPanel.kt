package com.kevingosse.docent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.trail.Section
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.Scrollable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * The section as a conversation (docs/DESIGN.md §6/§8). It opens with the section **narration** as the Docent's
 * first message and the human continues from there — one natural thread, not a separate "discuss" strip. The
 * Docent is the coding agent that authored the change, answering from first-hand knowledge ("the Docent
 * presents; the human asks", §4).
 *
 * Visual grammar (docs/UI.md §6): everything the Docent says sits on a tinted rounded card under its icon —
 * the same voice treatment as its inline comment cards — while the reviewer's messages stay plain. Live
 * replies render through the shared markup path ([DocentUi.appendMarkup]), so agent-emitted inline HTML shows
 * as italics/monospace instead of raw tags. While a reply is in flight an in-transcript
 * [DocentUi.ThinkingRow] marks where it will land (backend statuses stream into its label).
 *
 * Holds a [McpLoopBackend], created lazily on the first human message, that routes the remark to the connected
 * agent. The conversation is only usable when an agent is connected ([DocentReviewService.reviewActive]); when
 * none is, the input is disabled with a hint to connect one (there is no self-spawned fallback). The backend is
 * a child Disposable. All callbacks arrive off-EDT and are re-dispatched here via [onEdt].
 */
class SectionConversationPanel(
    private val project: Project,
    private val section: Section,
    private val sectionIndex: Int,
) : JPanel(BorderLayout()), Disposable {

    private val transcript = ScrollablePanel(VerticalLayout(JBUI.scale(10))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(10, 12)
    }
    private val input = JBTextArea().apply {
        rows = 2
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = "Ask the Docent about this section — Enter to send, Shift+Enter for a new line"
        border = JBUI.Borders.empty(4, 6)
    }
    private val sendButton = JButton("Send")

    private var backend: DocentConversationBackend? = null
    @Volatile private var disposed = false
    private var busy = false
    private var streaming: DocentBubble? = null

    /** The in-transcript "Docent is thinking…" row while a reply is in flight (removed on the first chunk). */
    private var thinking: DocentUi.ThinkingRow? = null

    init {
        val inputRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(
                JBScrollPane(input).apply {
                    border = BorderFactory.createCompoundBorder(
                        RoundedLineBorder(JBColor.border(), JBUI.scale(10)),
                        JBUI.Borders.empty(1),
                    )
                    isOpaque = false
                    viewport.isOpaque = false
                },
                BorderLayout.CENTER,
            )
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(sendButton, BorderLayout.SOUTH) // pin the button to the input's bottom edge as it grows
                },
                BorderLayout.EAST,
            )
        }
        add(
            JBScrollPane(transcript).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = JBUI.scale(16)
            },
            BorderLayout.CENTER,
        )
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(6, 12, 10, 12)
                add(inputRow, BorderLayout.CENTER)
            },
            BorderLayout.SOUTH,
        )

        sendButton.addActionListener { send() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }
        })
        // Grow the input with its content (up to ~6 rows), and only offer Send when there's something to send.
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onInputChanged()
            override fun removeUpdate(e: DocumentEvent) = onInputChanged()
            override fun changedUpdate(e: DocumentEvent) = onInputChanged()
        })

        // The section narration is the Docent's opening message; the discussion flows on from it.
        transcript.add(docentCard(DocentUi.htmlPane(section.narration)))
        applyConnectionState()
        onInputChanged()
        SwingUtilities.invokeLater { transcript.scrollRectToVisible(Rectangle(0, 0, 1, 1)) }
    }

    private fun onInputChanged() {
        val connected = DocentReviewService.getInstance(project).reviewActive
        sendButton.isEnabled = connected && !busy && input.text.isNotBlank()
        val rows = input.lineCount.coerceIn(2, 6)
        if (rows != input.rows) {
            input.rows = rows
            input.revalidate()
            revalidate()
        }
    }

    /** Enable the input only when an agent is connected; otherwise hint the reviewer to connect one. The panel
     *  is rebuilt by [DocentPanel] when the connection flips, so reading the state at construction is enough. */
    private fun applyConnectionState() {
        val connected = DocentReviewService.getInstance(project).reviewActive
        input.isEnabled = connected
        sendButton.isEnabled = connected && !busy && input.text.isNotBlank()
        input.emptyText.text = if (connected) {
            "Ask the Docent about this section — Enter to send, Shift+Enter for a new line"
        } else {
            "Connect an agent (Docent panel → “Connect agent…”) to discuss this section"
        }
    }

    private fun send() {
        if (disposed || busy) return
        if (!DocentReviewService.getInstance(project).reviewActive) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        addUserBubble(text)
        busy = true
        sendButton.isEnabled = false
        streaming = null
        thinking = DocentUi.ThinkingRow().also { transcript.add(it) }
        refreshTranscript()
        backend().send(text, turn)
    }

    /** The live agent backend (MCP loop), created once on first use. Only reached when an agent is connected
     *  (the input is disabled otherwise), so there's no fallback branch. */
    private fun backend(): DocentConversationBackend {
        backend?.let { return it }
        val b = McpLoopBackend(project, sectionIndex, section.headline)
        backend = b
        Disposer.register(this, b)
        return b
    }

    private val turn = object : DocentConversationBackend.Turn {
        override fun onReply(t: String) = onEdt {
            removeThinking()
            val b = streaming ?: DocentBubble().also { streaming = it; transcript.add(docentCard(it)) }
            b.append(t)
            refreshTranscript()
        }
        override fun onStatus(t: String) = onEdt { thinking?.setText(t.ifBlank { "Docent is thinking…" }) }
        override fun onDone() = onEdt {
            if (streaming == null) addSystem("(no reply yet)")
            endTurn()
        }
        override fun onError(message: String) = onEdt { failTurn(message) }
    }

    private fun endTurn() {
        busy = false
        removeThinking()
        streaming = null
        onInputChanged()
        refreshTranscript()
    }

    private fun failTurn(message: String) {
        addSystem("Couldn't reach the Docent: $message")
        endTurn()
    }

    private fun removeThinking() {
        thinking?.let { transcript.remove(it) }
        thinking = null
    }

    // ----- transcript -----

    private fun refreshTranscript() {
        transcript.revalidate()
        transcript.repaint()
        scrollToBottom()
    }

    /** The reviewer's message: a green "You" over plain wrapping text — deliberately lighter than the
     *  Docent's cards, so the two voices scan apart at a glance. */
    private fun addUserBubble(text: String) {
        transcript.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 6)
                add(
                    JBLabel("You").apply {
                        font = font.deriveFont(Font.BOLD)
                        foreground = DocentUi.REVIEWER
                        border = JBUI.Borders.emptyBottom(2)
                    },
                    BorderLayout.NORTH,
                )
                add(
                    JBTextArea(text).apply {
                        isEditable = false
                        isOpaque = false
                        lineWrap = true
                        wrapStyleWord = true
                        border = null
                        font = UIUtil.getLabelFont()
                    },
                    BorderLayout.CENTER,
                )
            },
        )
        refreshTranscript()
    }

    private fun addSystem(text: String) {
        transcript.add(
            JBTextArea(text).apply {
                isEditable = false
                isOpaque = false
                lineWrap = true
                wrapStyleWord = true
                border = JBUI.Borders.empty(2, 6)
                font = UIUtil.getLabelFont().deriveFont(Font.ITALIC)
                foreground = JBColor.GRAY
            },
        )
        refreshTranscript()
    }

    /** Wrap [body] in the Docent's voice treatment: its icon + name over a tinted rounded card. */
    private fun docentCard(body: JComponent): JComponent =
        DocentUi.RoundedPanel(DocentUi.DOCENT_MSG_BG).apply {
            border = JBUI.Borders.empty(8, 10)
            add(
                JBLabel("Docent", DocentUi.ICON, SwingConstants.LEADING).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = DocentUi.DOCENT
                    iconTextGap = JBUI.scale(6)
                    border = JBUI.Borders.emptyBottom(4)
                },
                BorderLayout.NORTH,
            )
            add(body, BorderLayout.CENTER)
        }

    private fun scrollToBottom() = SwingUtilities.invokeLater {
        transcript.scrollRectToVisible(Rectangle(0, transcript.height - 1, 1, 1))
    }

    private fun onEdt(block: () -> Unit) =
        ApplicationManager.getApplication().invokeLater({ if (!disposed) block() }, ModalityState.any())

    override fun dispose() {
        disposed = true
        // The backend is registered as our child Disposable, so Disposer tears it down (unregisters the
        // broker sinks, or kills the self-spawned agent process) when we are disposed.
    }

    // ----- small components -----

    /**
     * A streaming Docent reply: raw chunks accumulate in a buffer and re-render through the shared markup
     * path on each append, so inline HTML (`<code>`, `<em>`, …) shows styled — never as raw tags.
     */
    private class DocentBubble : JPanel(BorderLayout()) {
        private val pane = JTextPane().apply {
            isEditable = false
            isOpaque = false
            border = null
            font = UIUtil.getLabelFont()
        }
        private val buffer = StringBuilder()

        init {
            isOpaque = false
            add(pane, BorderLayout.CENTER)
        }

        fun append(t: String) {
            buffer.append(t)
            val doc = pane.styledDocument
            doc.remove(0, doc.length)
            DocentUi.appendMarkup(doc, buffer.toString())
            revalidate()
        }
    }

    /** Fills the scroll viewport's width so the wrapping text areas wrap to it (and scroll vertically). */
    private class ScrollablePanel(layout: LayoutManager) : JPanel(layout), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, orientation: Int, direction: Int) = JBUI.scale(100)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
}
