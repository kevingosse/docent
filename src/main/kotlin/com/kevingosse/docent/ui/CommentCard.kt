package com.kevingosse.docent.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.KeyStroke
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * A live, GitHub-PR-style inline comment card embedded in the editor. The reviewer can reply,
 * collapse any card, delete their own, or compose a brand-new comment. Read-write, in-session.
 *
 * No "Resolve": unlike GitHub (where Resolve means "I applied the suggestion"), here the reviewer
 * *is* the reviewer, so collapse + delete are the right verbs.
 */
class CommentCard(private val thread: CommentThread) : JPanel(BorderLayout()) {

    /** Invoked after the card changes height, so the host can refresh the embedding inlay. */
    var onChanged: (() -> Unit)? = null

    /** Invoked to remove this card entirely (delete, or cancel a compose). */
    var onRemove: (() -> Unit)? = null

    /** Routes the reviewer's reply / new comment to the live Docent; null → comments stay local. */
    var poster: CommentPoster? = null

    private fun isDocent() = thread.author.equals("docent", ignoreCase = true)
    private fun isUser() = thread.author.equals("you", ignoreCase = true)

    init {
        isOpaque = true
        background = if (isDocent()) JBColor(Color(0xF4F7FE), Color(0x2B3040)) else JBColor(Color(0xF3FAF3), Color(0x2C342C))
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(8, 10),
        )
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        add(
            when {
                thread.composing -> composeView()
                thread.collapsed -> collapsedView()
                else -> threadView()
            },
            BorderLayout.CENTER,
        )
        revalidate()
        repaint()
        onChanged?.invoke()
    }

    /** Send the reviewer's words to the live Docent; append its reply to this thread when it lands (EDT). */
    private fun routeToAgent(reviewerText: String) {
        val p = poster ?: return
        p.post(thread, reviewerText) { reply ->
            thread.replies.add(Reply("docent", reply))
            if (thread.collapsed) thread.collapsed = false
            rebuild()
        }
    }

    private fun threadView(): JComponent {
        val content = column()
        content.add(headerRow(expanded = true))
        content.add(bodyArea(thread.body))
        thread.replies.forEach { content.add(replyView(it)) }

        val input = inputArea("Write a reply…  (Ctrl+Enter to send)")
        val send = {
            val text = input.text.trim()
            if (text.isNotEmpty()) {
                thread.replies.add(Reply("you", text))
                routeToAgent(text)
                rebuild()
            }
        }
        input.onCtrlEnter(send)
        content.add(input)
        content.add(row(JButton("Reply").apply { addActionListener { send() } }))
        return content
    }

    private fun collapsedView(): JComponent =
        column(JBUI.scale(2)).apply {
            add(headerRow(expanded = false))
            add(JBLabel(preview(thread.body)).apply { foreground = JBColor.GRAY })
        }

    private fun composeView(): JComponent {
        val content = column()
        content.add(authorLabel("you"))
        val input = inputArea("Add a comment…  (Ctrl+Enter to send)")
        val send = {
            val text = input.text.trim()
            if (text.isNotEmpty()) {
                thread.body = text
                thread.composing = false
                routeToAgent(text)
                rebuild()
            }
        }
        input.onCtrlEnter(send)
        content.add(input)
        content.add(row(
            JButton("Comment").apply { addActionListener { send() } },
            JButton("Cancel").apply { addActionListener { onRemove?.invoke() } },
        ))
        return content
    }

    private fun replyView(reply: Reply): JComponent =
        column(JBUI.scale(2)).apply {
            border = JBUI.Borders.emptyLeft(12)
            add(authorLabel(reply.author))
            add(bodyArea(reply.body))
        }

    // ----- header with collapse / delete -----

    private fun headerRow(expanded: Boolean): JComponent {
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        if (isUser()) {
            right.add(iconButton(AllIcons.Actions.Close, "Delete comment") { onRemove?.invoke() })
        }
        right.add(iconButton(
            if (expanded) AllIcons.General.ChevronUp else AllIcons.General.ChevronDown,
            if (expanded) "Collapse" else "Expand",
        ) { thread.collapsed = !thread.collapsed; rebuild() })

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(authorLabel(thread.author), BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }
    }

    // ----- small builders -----

    private fun iconButton(icon: Icon, tooltip: String, onClick: () -> Unit): JComponent =
        JBLabel(icon).apply {
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = onClick()
            })
        }

    private fun column(gap: Int = JBUI.scale(6)) = JPanel(VerticalLayout(gap)).apply { isOpaque = false }

    private fun row(vararg comps: JComponent) =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            comps.forEach { add(it) }
        }

    private fun authorLabel(author: String) =
        JBLabel(if (author.equals("docent", true)) "Docent" else "You").apply {
            font = font.deriveFont(Font.BOLD)
            foreground = if (author.equals("docent", true)) DOCENT else YOU
        }

    /**
     * Render a comment body. The authoring/Docent agents sprinkle a little inline HTML — `<em>`/`<i>`
     * (emphasis), `<code>` (inline code), `<strong>`/`<b>`, plus `<br>`/`<p>` breaks and entities. We
     * parse just those into a styled [JTextPane] (mixed fonts, unlike a plain text area) so they render
     * as italics / monospace instead of showing raw tags; everything else is stripped.
     */
    private fun bodyArea(text: String): JComponent = JTextPane().apply {
        isEditable = false
        isOpaque = false
        border = null
        font = UIUtil.getLabelFont()
        appendMarkup(styledDocument, text)
    }

    private fun appendMarkup(doc: StyledDocument, text: String) {
        var bold = false
        var italic = false
        var code = false
        fun flush(s: String) {
            if (s.isEmpty()) return
            val attr = SimpleAttributeSet()
            StyleConstants.setBold(attr, bold)
            StyleConstants.setItalic(attr, italic)
            if (code) {
                StyleConstants.setFontFamily(attr, Font.MONOSPACED)
                StyleConstants.setBackground(attr, CODE_BG)
            }
            doc.insertString(doc.length, decodeEntities(s), attr)
        }
        var last = 0
        for (m in TAG.findAll(text)) {
            flush(text.substring(last, m.range.first))
            val closing = m.groupValues[1] == "/"
            when (m.groupValues[2].lowercase()) {
                "em", "i" -> italic = !closing
                "strong", "b" -> bold = !closing
                "code" -> code = !closing
                "br" -> doc.insertString(doc.length, "\n", null)
                "p" -> if (closing) doc.insertString(doc.length, "\n", null)
            }
            last = m.range.last + 1
        }
        flush(text.substring(last))
    }

    /** Send on Ctrl+Enter (and Cmd+Enter on macOS); plain Enter still inserts a newline. */
    private fun JBTextArea.onCtrlEnter(action: () -> Unit) {
        val key = "docent-send"
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), key)
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), key)
        actionMap.put(key, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = action()
        })
    }

    private fun inputArea(placeholder: String) = JBTextArea().apply {
        rows = 2
        lineWrap = true
        wrapStyleWord = true
        font = UIUtil.getLabelFont()
        emptyText.text = placeholder
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.customLine(JBColor.border(), 1),
            JBUI.Borders.empty(4),
        )
    }

    private fun preview(text: String): String {
        val plain = decodeEntities(TAG.replace(text, " "))
        val oneLine = plain.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > 80) oneLine.take(77) + "…" else oneLine
    }

    /** Decode the handful of HTML entities the agents emit; `&amp;` last so it can't re-trigger others. */
    private fun decodeEntities(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    companion object {
        private val DOCENT = JBColor(Color(0x3574F0), Color(0x4A88FF))
        private val YOU = JBColor(Color(0x4C9A4E), Color(0x62B266))

        /** Subtle inline-code chip background (light / dark). */
        private val CODE_BG = JBColor(Color(0xE8EAED), Color(0x3A3F4B))

        /** Matches one HTML tag: group 1 = "/" if a closing tag, group 2 = tag name. */
        private val TAG = Regex("<\\s*(/?)\\s*([a-zA-Z]+)[^>]*>")
    }
}
