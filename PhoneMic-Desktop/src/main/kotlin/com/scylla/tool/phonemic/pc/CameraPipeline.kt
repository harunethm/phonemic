package com.scylla.tool.phonemic.pc

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.min

/**
 * Everything that happens to a decoded phone frame: compose it onto the fixed landscape
 * canvas (rotation, mirror), show it in the previews, and push it into every virtual webcam
 * (Phone Mic Camera on Windows 11, OBS Virtual Camera when installed). Called from
 * [VideoReceiver]'s reader thread; all methods are synchronized because a replacing phone
 * connection can briefly overlap the old one.
 */
class CameraPipeline(
    private val onPreview: (BufferedImage) -> Unit,
    private val onStatus: (text: String, tone: Tone) -> Unit
) {
    enum class Tone { MUTED, GOOD, WARN, ERROR }

    companion object {
        private const val FPS = 30
        private const val OPEN_RETRY_MS = 2000L
        private const val STATUS_INTERVAL_MS = 1000L
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }

    /** One virtual camera plus the buffers for its size (when it differs from the canvas). */
    private class Output(val camera: VirtualCamera) {
        var open = false
        var problem: String? = null
        var lastAttempt = 0L
        var scaled: BufferedImage? = null
        var nv12: ByteArray? = null
    }

    @Volatile var mirror = false
    @Volatile var userQuarterTurns = 0

    private val outputs = VirtualCameras.createAll().map { Output(it) }

    private var composer: FrameComposer? = null
    private var canvasNv12: ByteArray? = null
    private var closed = false
    private var connected = false

    private var framesInWindow = 0
    private var windowStart = 0L
    private var fps = 0
    private var lastStatus = 0L

    /** Receiver started: open the cameras that stay up while idle (in the background, it can take a second). */
    fun start() {
        onStatus("Waiting for phone camera", Tone.MUTED)
        Thread({ synchronized(this) { openIdleOutputs() } }, "camera-outputs").apply { isDaemon = true }.start()
    }

    /** Called after Phone Mic Camera got installed: try every camera again right away. */
    @Synchronized
    fun retryNow() {
        if (closed) return
        outputs.forEach { it.lastAttempt = 0L }
        if (!connected) openIdleOutputs()
    }

    private fun openIdleOutputs() {
        if (closed) return
        outputs.filter { it.camera.keepOpenWhileIdle && !it.open }.forEach { tryOpen(it, DEFAULT_WIDTH, DEFAULT_HEIGHT) }
        if (!connected) reportIdle()
    }

    private fun tryOpen(output: Output, canvasWidth: Int, canvasHeight: Int) {
        val size = output.camera.fixedSize
        output.lastAttempt = System.currentTimeMillis()
        output.problem = try {
            output.camera.open(size?.width ?: canvasWidth, size?.height ?: canvasHeight, FPS)
        } catch (e: Throwable) {
            "Could not start ${output.camera.label}: ${e.message}"
        }
        output.open = output.problem == null
    }

    @Synchronized
    fun onFrame(image: BufferedImage, quarterTurns: Int, @Suppress("UNUSED_PARAMETER") front: Boolean) {
        if (closed) return
        connected = true
        val c = composer ?: FrameComposer(image.width, image.height).also {
            composer = it
            canvasNv12 = ByteArray(it.width * it.height * 3 / 2)
            windowStart = System.currentTimeMillis()
            lastStatus = 0L
        }
        val canvas = c.compose(image, quarterTurns + userQuarterTurns, mirror)
        onPreview(canvas)

        val now = System.currentTimeMillis()
        var canvasConverted = false
        for (output in outputs) {
            if (!output.open && now - output.lastAttempt >= OPEN_RETRY_MS) tryOpen(output, c.width, c.height)
            if (!output.open) continue
            val size = output.camera.fixedSize
            val frame = if (size == null || (size.width == c.width && size.height == c.height)) {
                if (!canvasConverted) {
                    c.toNv12(canvas, canvasNv12!!)
                    canvasConverted = true
                }
                canvasNv12!!
            } else {
                scaleInto(output, canvas, size.width, size.height)
            }
            try {
                output.camera.write(frame)
            } catch (e: Exception) {
                output.problem = "${output.camera.label} stopped: ${e.message}"
                closeOutput(output)
            }
        }

        framesInWindow++
        if (now - windowStart >= 1000) {
            fps = framesInWindow
            framesInWindow = 0
            windowStart = now
        }
        if (now - lastStatus >= STATUS_INTERVAL_MS) {
            lastStatus = now
            reportLive(c)
        }
    }

    /** Letterboxes the canvas into a camera's fixed size, then converts that to NV12. */
    private fun scaleInto(output: Output, canvas: BufferedImage, width: Int, height: Int): ByteArray {
        val target = output.scaled ?: BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { output.scaled = it }
        val nv12 = output.nv12 ?: ByteArray(width * height * 3 / 2).also { output.nv12 = it }
        val scale = min(width.toDouble() / canvas.width, height.toDouble() / canvas.height)
        val w = (canvas.width * scale).toInt()
        val h = (canvas.height * scale).toInt()
        val g = target.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(canvas, (width - w) / 2, (height - h) / 2, w, h, null)
        } finally {
            g.dispose()
        }
        rgbToNv12(target, nv12)
        return nv12
    }

    private fun reportLive(c: FrameComposer) {
        val size = "${c.width}×${c.height} · ${if (fps > 0) fps else FPS} fps"
        val open = outputs.filter { it.open }.map { it.camera.label }
        val problem = outputs.firstNotNullOfOrNull { it.problem }
        when {
            open.isNotEmpty() -> onStatus("$size → ${open.joinToString(" + ")}", Tone.GOOD)
            outputs.isEmpty() -> onStatus("$size · preview only. Install OBS Studio for a webcam", Tone.WARN)
            problem == VirtualCameras.BUSY -> onStatus(problem, Tone.WARN)
            else -> onStatus("$size · preview only. ${problem ?: "Starting virtual camera"}", Tone.WARN)
        }
    }

    private fun reportIdle() {
        val open = outputs.filter { it.open }.map { it.camera.label }
        if (open.isEmpty()) {
            onStatus("Waiting for phone camera", Tone.MUTED)
        } else {
            onStatus("Waiting for phone camera · ${open.joinToString(" + ")} ready", Tone.MUTED)
        }
    }

    /** Phone video ended: release cameras that shouldn't freeze on the last frame (OBS shows its placeholder). */
    @Synchronized
    fun disconnected() {
        if (closed) return
        connected = false
        outputs.filter { !it.camera.keepOpenWhileIdle }.forEach {
            closeOutput(it)
            it.problem = null
        }
        composer = null
        canvasNv12 = null
        fps = 0
        reportIdle()
    }

    @Synchronized
    fun close() {
        closed = true
        outputs.forEach { closeOutput(it) }
        composer = null
        canvasNv12 = null
    }

    private fun closeOutput(output: Output) {
        if (output.open) {
            try { output.camera.close() } catch (_: Exception) {}
        }
        output.open = false
        output.lastAttempt = 0L
    }
}
