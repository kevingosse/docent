package com.kevingosse.docent.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kevingosse.docent.DocentReviewService
import com.kevingosse.docent.trail.Section
import com.kevingosse.docent.trail.Trail
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.LayoutManager
import java.awt.Rectangle
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities

/**
 * The section as a conversation (docs/DESIGN.md §6/§8). It opens with the section **narration** as the Docent's
 * first message and the human continues from there — one natural thread, not a separate "discuss" strip. The
 * Docent is the coding agent that authored the change, answering from first-hand knowledge ("the Docent
 * presents; the human asks", §4).
 *
 * Holds a [DocentConversationBackend] chosen on the first human message: the live workbench agent via the MCP
 * loop when a review is active, otherwise a self-spawned agent (so skimmed sections spawn nothing). The backend
 * is a child Disposable. All callbacks arrive off-EDT and are re-dispatched here via [onEdt].
 */
class SectionConversationPanel(
    private val project: Project,
    private val trail: Trail,
    private val section: Section,
    private val sectionIndex: Int,
) : JPanel(BorderLayout()), Disposable {

    private val transcript = ScrollablePanel(VerticalLayout(JBUI.scale(10))).apply {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10)
    }
    private val input = JBTextArea().apply {
        rows = 2
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = "Ask the Docent about this section — Enter to send, Shift+Enter for a new line"
        border = JBUI.Borders.empty(4)
    }
    private val sendButton = JButton("Send")
    private val status = JBLabel("").apply { foreground = JBColor.GRAY }

    private var backend: DocentConversationBackend? = null
    @Volatile private var disposed = false
    private var busy = false
    private var streaming: Bubble? = null

    init {
        val inputRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(
                JBScrollPane(input).apply {
                    border = BorderFactory.createLineBorder(JBColor.border())
                    preferredSize = Dimension(0, JBUI.scale(52))
                },
                BorderLayout.CENTER,
            )
            add(sendButton, BorderLayout.EAST)
        }
        val south = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(2, 10, 8, 10)
            add(status.apply { border = JBUI.Borders.emptyBottom(3) }, BorderLayout.NORTH)
            add(inputRow, BorderLayout.CENTER)
        }
        add(
            JBScrollPane(transcript).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = JBUI.scale(16)
            },
            BorderLayout.CENTER,
        )
        add(south, BorderLayout.SOUTH)

        sendButton.addActionListener { send() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }
        })

        // The section narration is the Docent's opening message; the discussion flows on from it.
        addDocentHtml(section.narration)
    }

    private fun send() {
        if (disposed || busy) return
        val text = input.text.trim()
        if (text.isEmpty()) return
        input.text = ""
        addBubble("you", text)
        busy = true
        sendButton.isEnabled = false
        streaming = null
        backend().send(text, turn)
    }

    /** Pick the backend once: the live workbench agent (MCP loop) when a review is active, else self-spawn. */
    private fun backend(): DocentConversationBackend {
        backend?.let { return it }
        val service = DocentReviewService.getInstance(project)
        val b: DocentConversationBackend =
            if (service.reviewActive) McpLoopBackend(project, sectionIndex, section.headline)
            else SelfSpawnBackend(project, trail, section)
        backend = b
        Disposer.register(this, b)
        return b
    }

    private val turn = object : DocentConversationBackend.Turn {
        override fun onReply(t: String) = onEdt {
            val b = streaming ?: addBubble("docent", "").also { streaming = it }
            b.append(t)
            scrollToBottom()
        }
        override fun onStatus(t: String) = onEdt { status.text = t }
        override fun onDone() = onEdt {
            if (streaming == null) addSystem("(no reply yet)")
            endTurn()
        }
        override fun onError(message: String) = onEdt { failTurn(message) }
    }

    private fun endTurn() {
        busy = false
        sendButton.isEnabled = true
        status.text = ""
        streaming = null
        scrollToBottom()
    }

    private fun failTurn(message: String) {
        addSystem("Couldn't reach the Docent: $message")
        endTurn()
    }

    // ----- transcript -----

    private fun addBubble(author: String, text: String): Bubble {
        val b = Bubble(author).apply { setText(text) }
        transcript.add(b)
        transcript.revalidate()
        transcript.repaint()
        scrollToBottom()
        return b
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
        transcript.revalidate()
        transcript.repaint()
        scrollToBottom()
    }

    /** The Docent's opening message: the section narration, rendered as HTML so its formatting survives. */
    private fun addDocentHtml(html: String) {
        val body = JEditorPane("text/html", wrapHtml(html)).apply {
            isEditable = false
            isOpaque = false
            border = null
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }
        transcript.add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 6)
                add(
                    JBLabel(displayName("docent")).apply {
                        font = font.deriveFont(Font.BOLD)
                        foreground = colorFor("docent")
                        border = JBUI.Borders.emptyBottom(2)
                    },
                    BorderLayout.NORTH,
                )
                add(body, BorderLayout.CENTER)
            },
        )
        transcript.revalidate()
        transcript.repaint()
        SwingUtilities.invokeLater { transcript.scrollRectToVisible(Rectangle(0, 0, 1, 1)) }
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

    /** One transcript entry: a bold author label over a wrapping body. */
    private inner class Bubble(author: String) : JPanel(BorderLayout()) {
        private val body = JBTextArea().apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            border = null
            font = UIUtil.getLabelFont()
        }

        init {
            isOpaque = false
            border = JBUI.Borders.empty(2, 6)
            add(
                JBLabel(displayName(author)).apply {
                    font = font.deriveFont(Font.BOLD)
                    foreground = colorFor(author)
                    border = JBUI.Borders.emptyBottom(2)
                },
                BorderLayout.NORTH,
            )
            add(body, BorderLayout.CENTER)
        }

        fun setText(t: String) {
            body.text = t
            revalidate()
        }

        fun append(t: String) {
            body.text = body.text + t
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

    private fun displayName(author: String) = when {
        author.equals("docent", true) -> "Docent"
        author.equals("you", true) -> "You"
        else -> author
    }

    private fun colorFor(author: String) = if (author.equals("docent", true)) DOCENT else YOU

    companion object {
        private val DOCENT = JBColor(Color(0x3574F0), Color(0x4A88FF))
        private val YOU = JBColor(Color(0x4C9A4E), Color(0x62B266))
    }
}
