package com.kevingosse.docent.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.border.Border
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * The Docent's visual identity — the single source of truth for every review surface (docs/UI.md §3).
 * Palette, icon, HTML/markup rendering, and the small shared components (cards, chips, progress,
 * wrapping text, empty/loading states). Nothing in `ui/` should define its own colors or re-invent
 * "make text wrap in a box"; it should reach for this kit.
 */
object DocentUi {

    // ---- palette (light / dark) --------------------------------------------------------------------

    /** The Docent's voice: narration headers, chat identity, progress. One blue, everywhere it speaks. */
    val DOCENT = JBColor(Color(0x3574F0), Color(0x4A88FF))

    /** The reviewer's voice ("You"). */
    val REVIEWER = JBColor(Color(0x4C9A4E), Color(0x62B266))

    /** Reserved for the future different-model critic — the third comment layer (docs/ASSESSMENT.md §6). */
    @Suppress("unused")
    val CRITIC = JBColor(Color(0x8E5BD0), Color(0xA47BE0))

    /** "Go" affordances: the connected dot, the primary start-review card border. */
    val GO = JBColor(Color(0x4C9A4E), Color(0x62B266))

    /** Hover wash for clickable rows/cards. */
    val HOVER_BG = JBColor(Color(0xEDF4FE), Color(0x2E3034))

    /** Subtle inline-code chip background. */
    val CODE_BG = JBColor(Color(0xE8EAED), Color(0x3A3F4B))

    /** Tinted background behind a Docent utterance (chat card, docent comment card). */
    val DOCENT_MSG_BG = JBColor(Color(0xF4F7FE), Color(0x2B3040))

    /** Tinted background behind a reviewer comment card. */
    val REVIEWER_MSG_BG = JBColor(Color(0xF3FAF3), Color(0x2C342C))

    /** Diff-style added/removed accents for derived "+X −Y" stats. */
    val ADDED = JBColor(Color(0x368746), Color(0x57965C))
    val REMOVED = JBColor(Color(0xC7222D), Color(0xDB5C5C))

    /** The Docent glyph (the tool-window icon), reused as its avatar wherever it speaks. */
    val ICON: Icon by lazy { IconLoader.getIcon("/icons/docent.svg", DocentUi::class.java) }

    // ---- HTML (rich prose: trail narration / thesis) -----------------------------------------------

    /** Wrap agent-authored HTML in the label font + theme colors; [maxWidthPx] > 0 constrains the measure. */
    fun wrapHtml(body: String, maxWidthPx: Int = 0, foreground: Color? = null): String {
        val f = JBUI.Fonts.label()
        val fg = ColorUtil.toHtmlColor(foreground ?: JBColor.foreground())
        val width = if (maxWidthPx > 0) "width:${maxWidthPx}px;" else ""
        return "<html><head><style>" +
            "body{font-family:'${f.family}';font-size:${f.size}pt;color:$fg;margin:0;padding:0;$width}" +
            "p{margin:0 0 10px 0;} ul{margin:0 0 10px 18px;padding:0;} li{margin:0 0 6px 0;}" +
            "code{font-family:monospace;}" +
            "</style></head><body>$body</body></html>"
    }

    /** A read-only, transparent HTML pane for trail prose (narration, thesis). */
    fun htmlPane(bodyHtml: String, maxWidthPx: Int = 0): JEditorPane =
        JEditorPane("text/html", wrapHtml(bodyHtml, maxWidthPx)).apply {
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }

    fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** A colored "+X −Y" HTML fragment for derived diff stats (0-valued sides are dropped). */
    fun plusMinusHtml(added: Int, removed: Int): String {
        val parts = mutableListOf<String>()
        if (added > 0) parts += "<font color='${ColorUtil.toHtmlColor(ADDED)}'>+$added</font>"
        if (removed > 0) parts += "<font color='${ColorUtil.toHtmlColor(REMOVED)}'>&minus;$removed</font>"
        return parts.joinToString("&nbsp;")
    }

    // ---- lightweight markup (live agent text: chat replies, comment bodies) ------------------------

    /** Matches one HTML tag: group 1 = "/" if a closing tag, group 2 = tag name. */
    private val TAG = Regex("<\\s*(/?)\\s*([a-zA-Z]+)[^>]*>")

    /**
     * Render the inline-HTML subset the agents emit — `<em>`/`<i>`, `<strong>`/`<b>`, `<code>`, plus
     * `<p>`/`<br>`/`<ul>`/`<li>` breaks and entities — into a styled document (mixed fonts). Unknown
     * tags are stripped. This is the ONE rendering path for live agent text, shared by the chat
     * transcript and the inline comment cards, so the Docent's voice is typographically identical
     * everywhere (docs/UI.md §6).
     */
    fun appendMarkup(doc: StyledDocument, text: String) {
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
                "li" -> if (!closing) doc.insertString(doc.length, "\n• ", null)
                "ul", "ol" -> if (closing) doc.insertString(doc.length, "\n", null)
            }
            last = m.range.last + 1
        }
        flush(text.substring(last))
    }

    /** A read-only, transparent pane rendering [text] through [appendMarkup]. */
    fun markupPane(text: String): JTextPane = JTextPane().apply {
        isEditable = false
        isOpaque = false
        border = null
        font = UIUtil.getLabelFont()
        appendMarkup(styledDocument, text)
    }

    /** Strip markup + entities and crop to one short line (collapsed-card previews). */
    fun preview(text: String, max: Int = 80): String {
        val plain = decodeEntities(TAG.replace(text, " "))
        val oneLine = plain.replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > max) oneLine.take(max - 3) + "…" else oneLine
    }

    /** Decode the handful of HTML entities the agents emit; `&amp;` last so it can't re-trigger others. */
    fun decodeEntities(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    // ---- shared components --------------------------------------------------------------------------

    /** Rounded line border + inner padding — the standard Docent card edge. */
    fun cardBorder(accent: Color, padV: Int = 8, padH: Int = 10): Border = BorderFactory.createCompoundBorder(
        RoundedLineBorder(accent, JBUI.scale(10)),
        JBUI.Borders.empty(padV, padH),
    )

    /** A panel that paints a rounded [bg] fill (pairs with [cardBorder]'s rounded line). */
    open class RoundedPanel(var bg: Color?, private val arc: Int = 10) : JPanel(BorderLayout()) {
        init { isOpaque = false }
        override fun paintComponent(g: Graphics) {
            bg?.let { fill ->
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = fill
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(arc), JBUI.scale(arc))
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /**
     * Make a whole component clickable. The listener goes on it *and every child*, because Swing delivers a
     * mouse event to the deepest component that is itself a mouse target — a label with a tooltip becomes
     * one, so the click never reaches the row otherwise. Fires on mousePressed so a pixel of drag between
     * press and release can't swallow it.
     */
    fun installClick(row: JComponent, action: () -> Unit) {
        val adapter = object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = action()
        }
        fun attach(c: Component) {
            c.addMouseListener(adapter)
            if (c is Container) c.components.forEach { attach(it) }
        }
        attach(row)
    }

    /** Hover wash for a plain opaque-on-hover row (the nav rail rows). */
    fun installHover(row: JPanel) {
        val adapter = object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                row.isOpaque = true
                row.background = HOVER_BG
                row.repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                val p = javax.swing.SwingUtilities.convertPoint(e.component, e.point, row)
                if (!row.contains(p)) {
                    row.isOpaque = false
                    row.repaint()
                }
            }
        }
        fun attach(c: Component) {
            c.addMouseListener(adapter)
            if (c is Container) c.components.forEach { attach(it) }
        }
        attach(row)
    }

    /** Hover wash for a [RoundedPanel] (cards, chips): swaps the rounded fill instead of going opaque. */
    fun installHover(panel: RoundedPanel, hover: Color = HOVER_BG) {
        val rest = panel.bg
        val adapter = object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) {
                panel.bg = hover
                panel.repaint()
            }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                val p = javax.swing.SwingUtilities.convertPoint(e.component, e.point, panel)
                if (!panel.contains(p)) {
                    panel.bg = rest
                    panel.repaint()
                }
            }
        }
        fun attach(c: Component) {
            c.addMouseListener(adapter)
            if (c is Container) c.components.forEach { attach(it) }
        }
        attach(panel)
    }

    /**
     * The trail's position at a glance: one small segment per section — bright = you are here,
     * mid = visited, faint = still ahead. Ambient progress for the review pane header and the nav rail.
     */
    class SegmentedProgress(count: Int, visited: Set<Int>, current: Int) : JComponent() {
        private val count = count.coerceAtLeast(1)
        private val visited = visited
        private val current = current

        init {
            val w = JBUI.scale((this.count * 18).coerceAtMost(160))
            preferredSize = Dimension(w, JBUI.scale(5))
            minimumSize = preferredSize
            maximumSize = preferredSize
            toolTipText = null
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val gap = JBUI.scale(3)
            val segW = ((width - gap * (count - 1)).toFloat() / count).coerceAtLeast(2f)
            val arc = height
            for (i in 0 until count) {
                g2.color = when {
                    i == current -> DOCENT
                    i in visited -> ColorUtil.withAlpha(DOCENT, 0.45)
                    else -> JBColor.border()
                }
                val x = (i * (segW + gap)).toInt()
                g2.fillRoundRect(x, 0, segW.toInt().coerceAtLeast(2), height, arc, arc)
            }
            g2.dispose()
        }
    }

    /** A FlowLayout that actually wraps to the container width (for the file-chip row). */
    class WrapLayout(hgap: Int, vgap: Int) : FlowLayout(LEFT, hgap, vgap) {
        override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)
        override fun minimumLayoutSize(target: Container): Dimension =
            layoutSize(target, false).apply { width -= hgap + 1 }

        private fun layoutSize(target: Container, preferred: Boolean): Dimension {
            synchronized(target.treeLock) {
                // Wrap to the target's width when laid out; before the first layout use the parent's, so a
                // BorderLayout.NORTH host still gets a sane multi-row height on its first pass.
                val targetWidth = sequenceOf(target.width, target.parent?.width ?: 0)
                    .firstOrNull { it > 0 } ?: Int.MAX_VALUE
                val insets = target.insets
                val maxWidth = targetWidth - (insets.left + insets.right + hgap * 2)
                val dim = Dimension(0, 0)
                var rowWidth = 0
                var rowHeight = 0
                fun addRow() {
                    dim.width = maxOf(dim.width, rowWidth)
                    if (dim.height > 0) dim.height += vgap
                    dim.height += rowHeight
                }
                for (i in 0 until target.componentCount) {
                    val c = target.getComponent(i)
                    if (!c.isVisible) continue
                    val d = if (preferred) c.preferredSize else c.minimumSize
                    if (rowWidth + d.width > maxWidth && rowWidth > 0) {
                        addRow(); rowWidth = 0; rowHeight = 0
                    }
                    if (rowWidth != 0) rowWidth += hgap
                    rowWidth += d.width
                    rowHeight = maxOf(rowHeight, d.height)
                }
                addRow()
                dim.width += insets.left + insets.right + hgap * 2
                dim.height += insets.top + insets.bottom + vgap * 2
                return dim
            }
        }
    }

    /**
     * A wrapping, read-only prose area (JBTextArea wraps while painting, but box layouts ask preferred
     * height before assigning width — size it to the available width first). [widthProvider] supplies
     * that width when the parent can't (e.g. nested in a card measured before layout).
     */
    class WrappingText(text: String, private val widthProvider: (() -> Int)? = null) : JBTextArea(text) {
        init {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            font = UIUtil.getLabelFont()
        }

        override fun getPreferredSize(): Dimension {
            val available = widthProvider?.invoke()?.takeIf { it > 0 } ?: parent?.width?.takeIf { it > 0 } ?: width
            if (available > 0) setSize(available, Short.MAX_VALUE.toInt())
            return super.getPreferredSize().apply { if (available > 0) width = available }
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun getAlignmentX(): Float = LEFT_ALIGNMENT
    }

    /**
     * A word-wrapping *styled* (HTML) label whose wrap width comes from [widthProvider] **at every layout
     * pass** — never from a cached width baked into the HTML (the recurring clipping bug: box layouts ask
     * preferred height before assigning width, and a width cached at build time goes stale on resize).
     * The HTML flavor of [WrappingText]; use it wherever a rail row needs bold/colored fragments.
     * [foreground] overrides the theme text color (e.g. selection foreground on a highlighted row).
     */
    class WrappingHtmlPane(
        html: String,
        foreground: Color? = null,
        private val widthProvider: () -> Int,
    ) : JEditorPane("text/html", wrapHtml(html, 0, foreground)) {
        init {
            isEditable = false
            isOpaque = false
            isFocusable = false
            border = null
            putClientProperty(HONOR_DISPLAY_PROPERTIES, true)
        }

        override fun getPreferredSize(): Dimension {
            val available = widthProvider().takeIf { it > JBUI.scale(20) }
                ?: return super.getPreferredSize() // not laid out yet → unwrapped, corrected on the next pass
            setSize(available, Short.MAX_VALUE.toInt())
            return Dimension(available, super.getPreferredSize().height)
        }

        override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        override fun getAlignmentX(): Float = Component.LEFT_ALIGNMENT
    }

    /** An in-flow "the Docent is working" row: spinner + a mutable gray label (chat + comment cards). */
    class ThinkingRow(initial: String = "Docent is thinking…") : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)) {
        private val label = JBLabel(initial).apply { foreground = JBColor.GRAY }
        init {
            isOpaque = false
            add(JBLabel(AnimatedIcon.Default()))
            add(label)
        }
        fun setText(t: String) { label.text = t }
    }

    /** A centered spinner + message, for async loads (the diff host). */
    fun loadingState(text: String): JComponent = JPanel(GridBagLayout()).apply {
        isOpaque = false
        add(JPanel(FlowLayout(FlowLayout.CENTER, JBUI.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(AnimatedIcon.Default()))
            add(JBLabel(text).apply { foreground = JBColor.GRAY })
        })
    }

    /** A centered empty state: the Docent glyph, a title, and gray body prose (docs/UI.md §8). */
    fun emptyState(title: String, body: String?): JComponent {
        val column = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel(ICON).apply { alignmentX = Component.CENTER_ALIGNMENT })
            add(JBLabel(title).apply {
                font = JBFont.h4()
                alignmentX = Component.CENTER_ALIGNMENT
                border = JBUI.Borders.emptyTop(8)
            })
            if (!body.isNullOrBlank()) {
                add(JBLabel("<html><div style='width:${JBUI.scale(340)}px;text-align:center'>${escapeHtml(body).replace("\n", "<br>")}</div></html>").apply {
                    foreground = JBColor.GRAY
                    alignmentX = Component.CENTER_ALIGNMENT
                    border = JBUI.Borders.emptyTop(6)
                })
            }
        }
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(column, GridBagConstraints())
        }
    }
}
