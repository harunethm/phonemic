package com.scylla.tool.phonemic.pc

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.MatteBorder
import kotlin.math.log10
import kotlin.system.exitProcess

// Brand palette, matches docs/index.html landing page and the Android app theme.
private object Brand {
    val bg = Color(0x0F, 0x22, 0x29)
    val bg2 = Color(0x16, 0x32, 0x3C)
    val bg3 = Color(0x1C, 0x3D, 0x48)
    val edge = Color(0x2A, 0x45, 0x52)
    val ink = Color(0xEA, 0xF3, 0xF1)
    val inkMuted = Color(0x9F, 0xB8, 0xB7)
    val inkFaint = Color(0x5E, 0x80, 0x7E)
    val accent = Color(0xF2, 0xA3, 0x4D)
    val accentInk = Color(0x24, 0x14, 0x05)
    val accent2 = Color(0x59, 0xC7, 0xB0)
    val good = Color(0x5C, 0xB8, 0x5C)
    val warn = Color(0xE0, 0x95, 0x4A)
    val critical = Color(0xE0, 0x67, 0x67)
    val criticalInk = Color(0x2A, 0x08, 0x08)
}

private fun colorForQuality(label: String): Color = when (label) {
    "Excellent", "Good" -> Brand.good
    "Poor" -> Brand.warn
    "Critical" -> Brand.critical
    else -> Brand.inkFaint
}

private fun label(text: String, color: Color, size: Float = 13f, bold: Boolean = false): JLabel {
    val l = JLabel(text)
    l.foreground = color
    l.font = l.font.deriveFont(if (bold) Font.BOLD else Font.PLAIN, size)
    l.alignmentX = JLabel.LEFT_ALIGNMENT
    return l
}

class ReceiverUi : JFrame("Phone Mic - Desktop Receiver") {

    companion object {
        private const val WINDOW_WIDTH = 720
        private const val WINDOW_HEIGHT = 500
        private const val CONTENT_PADDING = 24
        // Sidebar (QR side) is 2/5 of the window width, matching the "dominant QR" design intent.
        private const val SIDEBAR_WIDTH = (WINDOW_WIDTH * 2) / 5
    }

    private data class MixerItem(val info: Mixer.Info) {
        override fun toString(): String = info.name
    }

    private val deviceSelect = SelectRow<MixerItem> { it.info.name }
    private val portRow = EditableRow(PcPrefs.getPort().toString())
    private val toggleButton = PillButton("▶  Start Receiver")
    private val statusPill = StatusPill()
    private val packetsLabel = label("", Brand.inkFaint, 11f)
    private val levelBar = LevelMeter()
    private val boostSlider = BoostSlider(100, 500, PcPrefs.getBoost()) // 1.0x-5.0x, in 0.01x steps; 100 = 0dB
    private val boostLabel = label(dbLabel(PcPrefs.getBoost()), Brand.accent2, 11.5f, bold = true)

    private val qrLabel = JLabel()
    private val codeLabel = label("", Brand.ink, 17f, bold = true)
    private val ipLabel = label("Detecting…", Brand.inkMuted, 12f)
    // Per the approved design: the CODE row's action is Reset (regenerate), the IP row's is Copy -
    // the mockup itself had these the other way round (copy-code / reset-ip), swapped on request.
    private val resetCodeButton = CircleIconButton(IconFont.REFRESH, filled = false)
    private val copyIpButton = IconTextButton(IconFont.COPY, "Copy", Brand.accent2)
    private val pairingLabel = label("Not paired", Brand.inkFaint, 11f, bold = true)

    private var currentPin = AudioReceiver.generatePin()
    private var receiver: AudioReceiver? = null

    init {
        defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        layout = BorderLayout()
        (contentPane as JPanel).border = EmptyBorder(CONTENT_PADDING, CONTENT_PADDING, CONTENT_PADDING, CONTENT_PADDING)
        contentPane.background = Brand.bg
        applyAppIcon()

        val body = JPanel(BorderLayout())
        body.isOpaque = false
        body.add(buildSidebar(), BorderLayout.WEST)
        body.add(buildControlsColumn(), BorderLayout.CENTER)
        add(body, BorderLayout.CENTER)

        populateMixers()
        renderPairingCode()
        toggleButton.addActionListener { onToggle() }
        portRow.field.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = renderPairingCode()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = renderPairingCode()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = renderPairingCode()
        })
        boostSlider.onChange = { value ->
            boostLabel.text = dbLabel(value)
            receiver?.gain = value / 100f
            PcPrefs.setBoost(value)
        }
        resetCodeButton.addActionListener { regeneratePairingCode() }
        copyIpButton.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(ipLabel.text), null)
        }
        deviceSelect.onSelectionChanged = { deviceSelect.selectedItem?.info?.name?.let { PcPrefs.setMixerName(it) } }

        applyIdleStyle()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) {
                receiver?.stop()
                exitProcess(0)
            }
        })

        setSize(WINDOW_WIDTH, WINDOW_HEIGHT)
        // Fixed at the initial size instead of a min/max range - the two-column layout
        // doesn't have a resize story yet, so just don't offer resizing at all.
        isResizable = false
        setLocationRelativeTo(null)
    }

    private fun buildSidebar(): JPanel {
        val col = JPanel()
        col.isOpaque = false
        col.layout = BoxLayout(col, BoxLayout.Y_AXIS)
        col.border = CompoundBorder(MatteBorder(0, 0, 0, 1, Brand.edge), EmptyBorder(0, 0, 0, 24))
        col.preferredSize = Dimension(SIDEBAR_WIDTH, 0)
        col.maximumSize = Dimension(SIDEBAR_WIDTH, Int.MAX_VALUE)

        val heading = label("Scan to pair", Brand.inkMuted, 12f, bold = true)
        heading.alignmentX = JLabel.CENTER_ALIGNMENT
        val subtitle = label("Open Phone Mic app on Android", Brand.inkFaint, 10.5f)
        subtitle.alignmentX = JLabel.CENTER_ALIGNMENT

        qrLabel.horizontalAlignment = JLabel.CENTER
        qrLabel.isOpaque = false

        val qrCard = RoundedPanel(14, Brand.ink)
        qrCard.layout = BorderLayout()
        qrCard.border = EmptyBorder(12, 12, 12, 12)
        qrCard.alignmentX = JLabel.CENTER_ALIGNMENT
        qrCard.preferredSize = Dimension(240, 240)
        qrCard.maximumSize = Dimension(240, 240)
        qrCard.add(qrLabel, BorderLayout.CENTER)

        val card = RoundedPanel(12, Brand.bg2, Brand.edge)
        card.layout = BorderLayout(8, 0)
        card.border = EmptyBorder(12, 14, 12, 14)
        card.alignmentX = JLabel.CENTER_ALIGNMENT
        card.maximumSize = Dimension(Int.MAX_VALUE, 64)

        val codeTextCol = JPanel()
        codeTextCol.isOpaque = false
        codeTextCol.layout = BoxLayout(codeTextCol, BoxLayout.Y_AXIS)
        codeTextCol.add(label("PAIRING CODE", Brand.inkFaint, 9.5f, bold = true))
        codeTextCol.add(codeLabel)
        card.add(codeTextCol, BorderLayout.CENTER)
        resetCodeButton.fillColor = Brand.bg3
        resetCodeButton.iconColor = Brand.inkMuted
        resetCodeButton.flashColor = Brand.accent2
        card.add(resetCodeButton, BorderLayout.EAST)

        val ipRow = JPanel(BorderLayout())
        ipRow.isOpaque = false
        ipRow.alignmentX = JLabel.CENTER_ALIGNMENT
        ipRow.maximumSize = Dimension(Int.MAX_VALUE, 28)
        ipRow.border = EmptyBorder(0, 4, 0, 4)
        ipLabel.icon = LucideIcon(IconFont.GLOBE, 13, Brand.inkFaint)
        ipLabel.iconTextGap = 6
        ipRow.add(ipLabel, BorderLayout.WEST)
        ipRow.add(copyIpButton, BorderLayout.EAST)

        pairingLabel.alignmentX = JLabel.CENTER_ALIGNMENT

        col.add(heading)
        col.add(Box.createVerticalStrut(2))
        col.add(subtitle)
        col.add(Box.createVerticalStrut(16))
        col.add(qrCard)
        col.add(Box.createVerticalStrut(18))
        col.add(card)
        col.add(Box.createVerticalStrut(10))
        col.add(ipRow)
        col.add(Box.createVerticalStrut(10))
        col.add(pairingLabel)
        col.add(Box.createVerticalGlue())
        return col
    }

    private fun buildControlsColumn(): JPanel {
        val col = JPanel(BorderLayout(0, 20))
        col.isOpaque = false
        col.border = EmptyBorder(0, 24, 0, 0)

        val fields = JPanel()
        fields.isOpaque = false
        fields.layout = BoxLayout(fields, BoxLayout.Y_AXIS)

        fun fieldBlock(caption: String, control: java.awt.Component): JPanel {
            val block = JPanel()
            block.isOpaque = false
            block.layout = BoxLayout(block, BoxLayout.Y_AXIS)
            block.alignmentX = JLabel.LEFT_ALIGNMENT
            val cap = label(caption, Brand.inkMuted, 11f, bold = true)
            block.add(cap)
            block.add(Box.createVerticalStrut(8))
            block.add(control)
            block.add(Box.createVerticalStrut(16))
            return block
        }

        deviceSelect.fillColor = Brand.bg2
        deviceSelect.edgeColor = Brand.edge
        deviceSelect.chevronColor = Brand.inkFaint
        deviceSelect.foreground = Brand.ink
        deviceSelect.alignmentX = JLabel.LEFT_ALIGNMENT

        portRow.fillColor = Brand.bg2
        portRow.edgeColor = Brand.edge
        portRow.field.foreground = Brand.ink
        portRow.field.caretColor = Brand.ink
        portRow.alignmentX = JLabel.LEFT_ALIGNMENT

        val boostHeader = JPanel(BorderLayout())
        boostHeader.isOpaque = false
        boostHeader.alignmentX = JLabel.LEFT_ALIGNMENT
        // BorderLayout.maximumLayoutSize() always reports Int.MAX_VALUE regardless of content -
        // every such row needs an explicit cap or BoxLayout hands it all the leftover space.
        boostHeader.maximumSize = Dimension(Int.MAX_VALUE, 22)
        boostHeader.add(label("Input Boost", Brand.inkMuted, 11f, bold = true), BorderLayout.WEST)
        boostHeader.add(boostLabel, BorderLayout.EAST)
        boostSlider.trackColor = Brand.bg3
        boostSlider.fillColor = Brand.accent2
        boostSlider.thumbColor = Brand.accent2
        boostSlider.alignmentX = JLabel.LEFT_ALIGNMENT

        val boostBlock = JPanel()
        boostBlock.isOpaque = false
        boostBlock.layout = BoxLayout(boostBlock, BoxLayout.Y_AXIS)
        boostBlock.alignmentX = JLabel.LEFT_ALIGNMENT
        boostBlock.add(boostHeader)
        boostBlock.add(Box.createVerticalStrut(10))
        boostBlock.add(boostSlider)

        fields.add(fieldBlock("Output device", deviceSelect))
        fields.add(fieldBlock("Listen port", portRow))
        fields.add(boostBlock)

        // BoxLayout's perpendicular-axis stretch (does a LEFT-aligned child expand to fill
        // the container's width?) turned out unreliable to depend on here - these wrappers
        // make it explicit instead: BorderLayout.CENTER unconditionally fills both dimensions
        // of its parent, and FlowLayout.CENTER centers its child within a now full-width row.
        fun stretchRow(component: java.awt.Component): JPanel {
            val wrap = JPanel(BorderLayout())
            wrap.isOpaque = false
            wrap.add(component, BorderLayout.CENTER)
            return wrap
        }
        fun centerRow(component: java.awt.Component): JPanel {
            val wrap = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0))
            wrap.isOpaque = false
            wrap.add(component)
            return wrap
        }

        val bottom = JPanel()
        bottom.isOpaque = false
        bottom.layout = BoxLayout(bottom, BoxLayout.Y_AXIS)
        levelBar.trackColor = Brand.bg3
        levelBar.fillColor = Brand.accent2
        toggleButton.pillHeight = 56
        toggleButton.font = toggleButton.font.deriveFont(Font.BOLD, 16f)
        bottom.add(stretchRow(levelBar))
        bottom.add(Box.createVerticalStrut(4))
        bottom.add(centerRow(packetsLabel))
        bottom.add(Box.createVerticalStrut(10))
        bottom.add(centerRow(statusPill))
        bottom.add(Box.createVerticalStrut(16))
        bottom.add(stretchRow(toggleButton))

        col.add(fields, BorderLayout.CENTER)
        col.add(bottom, BorderLayout.SOUTH)
        return col
    }

    private fun dbLabel(sliderValue: Int): String {
        val gain = sliderValue / 100f
        val db = 20f * log10(gain)
        return String.format("%+.1f dB", db)
    }

    private fun applyIdleStyle() {
        toggleButton.style(Brand.accent, Brand.accentInk)
        statusPill.setStatus("Stopped", Brand.inkFaint)
    }

    private fun renderPairingCode() {
        val ip = NetworkUtils.detectLocalIPv4()
        ipLabel.text = ip ?: "Unable to detect"
        codeLabel.text = currentPin
        val port = portRow.field.text.trim().toIntOrNull() ?: PcPrefs.getPort()
        qrLabel.icon = ImageIcon(encodeQrImage("${ip ?: "0.0.0.0"}:$port:$currentPin", 210))
    }

    private fun encodeQrImage(text: String, size: Int): BufferedImage {
        val matrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until size) {
            for (y in 0 until size) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        return image
    }

    private fun regeneratePairingCode() {
        currentPin = receiver?.regeneratePin() ?: AudioReceiver.generatePin()
        renderPairingCode()
        pairingLabel.text = "Not paired"
        pairingLabel.foreground = Brand.inkFaint
    }

    private fun populateMixers() {
        val lineInfo = DataLine.Info(SourceDataLine::class.java, AudioReceiver.FORMAT)
        val items = AudioSystem.getMixerInfo()
            .filter { AudioSystem.getMixer(it).isLineSupported(lineInfo) }
            .map { MixerItem(it) }
        deviceSelect.items = items
        if (items.isEmpty()) {
            statusPill.setStatus("No compatible output devices found", Brand.critical)
            toggleButton.isEnabled = false
            return
        }
        PcPrefs.getMixerName()?.let { deviceSelect.selectByName(it) }
    }

    private fun onToggle() {
        if (receiver == null) {
            val port = portRow.field.text.trim().toIntOrNull()
            val selected = deviceSelect.selectedItem
            if (port == null || port !in 1..65535 || selected == null) {
                statusPill.setStatus("Enter a valid port and select a device", Brand.critical)
                return
            }

            portRow.field.isEnabled = false
            deviceSelect.isEnabled = false
            toggleButton.text = "■  Stop Receiver"
            toggleButton.style(Brand.critical, Brand.criticalInk)
            statusPill.setStatus("Listening on port $port...", Brand.warn)
            pairingLabel.text = "Not paired"
            pairingLabel.foreground = Brand.inkFaint
            packetsLabel.text = ""

            PcPrefs.setPort(port)
            PcPrefs.setMixerName(selected.info.name)
            renderPairingCode()

            receiver = AudioReceiver(
                port = port,
                mixerInfo = selected.info,
                initialPin = currentPin,
                onStats = { received, lost, level ->
                    SwingUtilities.invokeLater {
                        val quality = qualityLabelFor(received, lost)
                        statusPill.setStatus(if (quality.isEmpty()) "Receiving…" else quality, colorForQuality(quality))
                        packetsLabel.text = "$received packets received · $lost lost"
                        levelBar.value = (level * 100).toInt()
                    }
                },
                onPaired = { senderAddress ->
                    SwingUtilities.invokeLater {
                        pairingLabel.text = if (senderAddress != null) "Paired with $senderAddress" else "Not paired"
                        pairingLabel.foreground = if (senderAddress != null) Brand.good else Brand.inkFaint
                    }
                },
                // Phone stopped streaming: stop here too so it isn't left running by mistake.
                onRemoteStop = { SwingUtilities.invokeLater { if (receiver != null) onToggle() } },
                onError = { message ->
                    SwingUtilities.invokeLater {
                        statusPill.setStatus("Error: $message", Brand.critical)
                        levelBar.value = 0
                        receiver?.stop()
                        receiver = null
                        resetControls()
                    }
                }
            ).also {
                it.gain = boostSlider.value / 100f
                it.start()
            }
        } else {
            receiver?.stop()
            receiver = null
            levelBar.value = 0
            pairingLabel.text = "Not paired"
            pairingLabel.foreground = Brand.inkFaint
            packetsLabel.text = ""
            resetControls()
        }
    }

    private fun qualityLabelFor(received: Long, lost: Long): String {
        val total = received + lost
        if (total <= 0) return ""
        val lossPercent = (lost * 100 / total)
        return when {
            lossPercent <= 1 -> "Excellent"
            lossPercent <= 5 -> "Good"
            lossPercent <= 15 -> "Poor"
            else -> "Critical"
        }
    }

    private fun resetControls() {
        portRow.field.isEnabled = true
        deviceSelect.isEnabled = true
        toggleButton.text = "▶  Start Receiver"
        applyIdleStyle()
    }

    /** Sets the window/taskbar icon everywhere, plus the macOS Dock icon when that API is available. */
    private fun applyAppIcon() {
        val icon = try {
            javaClass.classLoader.getResourceAsStream("icons/app-icon-256.png")?.use {
                javax.imageio.ImageIO.read(it)
            }
        } catch (_: Exception) {
            null
        } ?: return
        iconImage = icon
        if (java.awt.Taskbar.isTaskbarSupported()) {
            val taskbar = java.awt.Taskbar.getTaskbar()
            if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
                taskbar.iconImage = icon
            }
        }
    }
}
