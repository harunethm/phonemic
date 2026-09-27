package com.scylla.tool.phonemic.pc

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.border.EmptyBorder

/**
 * Bundled Lucide icon font (same icon set the approved HTML/Figma design uses via
 * `iconify-icon icon="lucide:*"`) - loaded once as a real Font object, so icon glyphs
 * are drawn from actual vector outlines instead of hand-derived Graphics2D shapes.
 * The earlier hand-drawn copy/refresh icons needed two rounds of fixes to look right;
 * this is the "ask, then use the robust option" follow-through on that.
 */
object IconFont {
    const val COPY = ""
    const val REFRESH = ""
    const val GLOBE = ""

    val font: Font? by lazy {
        try {
            javaClass.classLoader.getResourceAsStream("fonts/lucide.ttf")?.use {
                Font.createFont(Font.TRUETYPE_FONT, it)
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** javax.swing.Icon backed by an [IconFont] glyph, so it drops straight into any Swing icon slot. */
class LucideIcon(private val glyph: String, private val size: Int, private val color: Color) : Icon {
    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: java.awt.Component, g: Graphics, x: Int, y: Int) {
        val glyphFont = IconFont.font?.deriveFont(size.toFloat()) ?: return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.font = glyphFont
        g2.color = color
        val fm = g2.fontMetrics
        val gx = x + (size - fm.stringWidth(glyph)) / 2f
        val gy = y + (size - size) / 2f + fm.ascent + (size - (fm.ascent + fm.descent)) / 2f
        g2.drawString(glyph, gx, gy)
        g2.dispose()
    }
}

/** Plain rounded-rect surface: fill + optional 1px stroke, matching the design's `rounded-[Npx]` cards/rows. */
open class RoundedPanel(var radius: Int, var fillColor: Color? = null, var edgeColor: Color? = null) : JPanel() {
    init { isOpaque = false }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // fillRoundRect's arc params are corner DIAMETER, not radius - double it to get the CSS-style radius we store.
        val arc = radius * 2
        fillColor?.let {
            g2.color = it
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        edgeColor?.let {
            g2.color = it
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** Fully-rounded pill button, custom-painted so it never inherits the platform L&F's button chrome. */
open class PillButton(text: String, private val filled: Boolean = true) : JButton(text) {
    var fillColor: Color = Color.GRAY
    var edgeColor: Color? = null
    var pillHeight: Int = 44

    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.BOLD, 15f)
        horizontalAlignment = SwingConstants.CENTER
    }

    fun style(fill: Color, text: Color, edge: Color? = null) {
        fillColor = fill
        foreground = text
        edgeColor = edge
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width + 32, pillHeight)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = height
        if (filled) {
            g2.color = if (isEnabled) fillColor else fillColor.darker()
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        edgeColor?.let {
            g2.color = it
            g2.stroke = BasicStroke(1.5f)
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        }
        g2.dispose()
        super.paintComponent(g)
    }
}

/** Circular icon button (copy / regenerate); icon is a real [IconFont] glyph, not hand-drawn vector shapes. */
class CircleIconButton(private val glyph: String, private val filled: Boolean = true) : JButton() {
    var fillColor: Color = Color.DARK_GRAY
    var iconColor: Color = Color.LIGHT_GRAY
    var flashColor: Color = Color.WHITE
    private var flashing = false

    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        // Icon buttons give no other feedback that a click registered (no ripple, no text change) -
        // flash the fill briefly so "did that actually do anything?" has a visible answer.
        addActionListener {
            flashing = true
            repaint()
            javax.swing.Timer(180) { flashing = false; repaint() }.apply { isRepeats = false }.start()
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(32, 32)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (filled || flashing) {
            g2.color = if (flashing) flashColor else fillColor
            g2.fillOval(0, 0, width - 1, height - 1)
        }
        val iconSize = (width * 0.55f).toInt()
        val inset = (width - iconSize) / 2
        LucideIcon(glyph, iconSize, iconColor).paintIcon(this, g2, inset, inset)
        g2.dispose()
    }
}

/** Flat icon+label button (e.g. "Copy" next to the IP row) - built on Swing's native icon/text layout. */
class IconTextButton(glyph: String, text: String, private val tintColor: Color) : JButton(text) {
    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        foreground = tintColor
        font = font.deriveFont(Font.BOLD, 11.5f)
        icon = LucideIcon(glyph, 13, tintColor)
        iconTextGap = 6
        horizontalTextPosition = SwingConstants.RIGHT
        border = EmptyBorder(4, 4, 4, 4)
    }
}

/**
 * Custom-styled select row (label + chevron on a dark surface) replacing the native
 * combo-box look. Backed by a plain index + item list, popup-driven like a combo box.
 */
class SelectRow<T>(private val itemLabel: (T) -> String) : JButton() {
    var items: List<T> = emptyList()
        set(value) {
            field = value
            if (selectedIndex !in value.indices) selectedIndex = if (value.isNotEmpty()) 0 else -1
            refreshText()
        }
    var selectedIndex: Int = -1
        private set
    var onSelectionChanged: ((Int) -> Unit)? = null
    var fillColor: Color = Color.DARK_GRAY
    var edgeColor: Color = Color.GRAY
    var chevronColor: Color = Color.LIGHT_GRAY

    val selectedItem: T? get() = items.getOrNull(selectedIndex)

    init {
        isContentAreaFilled = false
        isFocusPainted = false
        isBorderPainted = false
        isOpaque = false
        horizontalAlignment = SwingConstants.LEFT
        margin = java.awt.Insets(0, 16, 0, 30)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addActionListener { showPopup() }
        refreshText()
    }

    fun selectByName(name: String) {
        val idx = items.indexOfFirst { itemLabel(it) == name }
        if (idx >= 0) {
            selectedIndex = idx
            refreshText()
        }
    }

    private fun refreshText() {
        text = selectedItem?.let(itemLabel) ?: "No device found"
    }

    private fun showPopup() {
        if (items.isEmpty()) return
        val menu = JPopupMenu()
        items.forEachIndexed { index, item ->
            val entry = JMenuItem(itemLabel(item))
            entry.addActionListener {
                selectedIndex = index
                refreshText()
                onSelectionChanged?.invoke(index)
            }
            menu.add(entry)
        }
        menu.show(this, 0, height)
    }

    override fun getPreferredSize(): Dimension = Dimension(super.getPreferredSize().width, 48)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 48)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fillColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, 24, 24)
        g2.color = edgeColor
        g2.drawRoundRect(0, 0, width - 1, height - 1, 24, 24)
        g2.color = chevronColor
        g2.font = g2.font.deriveFont(12f)
        val metrics = g2.fontMetrics
        g2.drawString("▾", width - 16 - metrics.stringWidth("▾"), height / 2 + metrics.ascent / 2 - 2)
        g2.dispose()
        super.paintComponent(g)
    }
}

/** Rounded row with an editable text field plus a decorative pencil icon, matching the "Listen port" row. */
class EditableRow(initialText: String) : RoundedPanel(12) {
    val field = JTextField(initialText)

    init {
        layout = BorderLayout()
        field.isOpaque = false
        field.border = EmptyBorder(0, 16, 0, 16)
        field.font = field.font.deriveFont(Font.PLAIN, 14f)
        add(field, BorderLayout.CENTER)
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        return Dimension(base.width.coerceAtLeast(120), 48)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 48)
}

/** Custom-painted horizontal slider: rounded track, filled portion, ring-style thumb - drag or click to set. */
class BoostSlider(private val minValue: Int, private val maxValue: Int, initial: Int) : JPanel() {
    var value: Int = initial
        set(v) {
            val clamped = v.coerceIn(minValue, maxValue)
            if (clamped != field) {
                field = clamped
                repaint()
                onChange?.invoke(field)
            }
        }
    var trackColor: Color = Color.DARK_GRAY
    var fillColor: Color = Color.CYAN
    var thumbColor: Color = Color.WHITE
    var onChange: ((Int) -> Unit)? = null

    init {
        isOpaque = false
        val listener = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (isEnabled) updateFromX(e.x)
            }
            override fun mouseDragged(e: MouseEvent) {
                if (isEnabled) updateFromX(e.x)
            }
        }
        addMouseListener(listener)
        addMouseMotionListener(listener)
    }

    private fun updateFromX(x: Int) {
        val usable = (width - 16).coerceAtLeast(1)
        val frac = ((x - 8).toFloat() / usable).coerceIn(0f, 1f)
        value = (minValue + frac * (maxValue - minValue)).toInt()
    }

    override fun getPreferredSize(): Dimension = Dimension(200, 24)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 24)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val trackH = 8
        val cy = height / 2
        val usable = width - 16
        val frac = (value - minValue).toFloat() / (maxValue - minValue).toFloat()
        val fillW = (usable * frac).toInt().coerceAtLeast(trackH)

        g2.color = trackColor
        g2.fillRoundRect(8, cy - trackH / 2, usable, trackH, trackH, trackH)
        g2.color = fillColor
        g2.fillRoundRect(8, cy - trackH / 2, fillW, trackH, trackH, trackH)

        val thumbR = 8
        val thumbX = 8 + fillW
        g2.color = thumbColor
        g2.fillOval(thumbX - thumbR, cy - thumbR, thumbR * 2, thumbR * 2)
        g2.color = fillColor
        g2.stroke = BasicStroke(2f)
        g2.drawOval(thumbX - thumbR, cy - thumbR, thumbR * 2, thumbR * 2)
        g2.dispose()
    }
}

/** Tinted status pill: dot + mono label on an 18%-opacity fill of the state color. */
class StatusPill : JPanel() {
    private val dotSize = 8
    private val label = JLabel()
    var color: Color = Color.GRAY
        set(v) {
            field = v
            label.foreground = v
            repaint()
        }

    init {
        isOpaque = false
        layout = FlowLayout(FlowLayout.LEFT, 6, 0)
        border = EmptyBorder(6, 12, 6, 12)
        label.font = label.font.deriveFont(Font.PLAIN, 11.5f)
        add(object : JPanel() {
            override fun getPreferredSize() = Dimension(dotSize, dotSize)
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                g2.fillOval(0, 0, width, height)
                g2.dispose()
            }
        }.also { it.isOpaque = false })
        add(label)
    }

    fun setStatus(text: String, c: Color) {
        label.text = "Status: $text"
        color = c
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(color.red, color.green, color.blue, 46)
        g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
        g2.dispose()
    }
}

/** Plain non-interactive VU-style meter: filled rounded bar, no thumb - visually distinct from BoostSlider. */
class LevelMeter : JPanel() {
    var value: Int = 0
        set(v) {
            field = v.coerceIn(0, 100)
            repaint()
        }
    var trackColor: Color = Color.DARK_GRAY
    var fillColor: Color = Color.CYAN

    init { isOpaque = false }

    override fun getPreferredSize(): Dimension = Dimension(200, 6)
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, 6)

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = trackColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, height, height)
        val fillW = (width * (value / 100f)).toInt()
        if (fillW > 0) {
            g2.color = fillColor
            g2.fillRoundRect(0, 0, fillW.coerceAtMost(width), height - 1, height, height)
        }
        g2.dispose()
    }
}
