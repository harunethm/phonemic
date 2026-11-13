package com.scylla.tool.phonemic.pc

import java.awt.image.BufferedImage

/**
 * Everything that happens to a decoded phone frame: compose it onto the fixed landscape
 * canvas (rotation, mirror), show it in the previews, and push it into the virtual webcam.
 * Called from [VideoReceiver]'s reader thread; all methods are synchronized because a
 * replacing phone connection can briefly overlap the old one.
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
    }

    @Volatile var mirror = false
    @Volatile var userQuarterTurns = 0

    private val camera: VirtualCamera? = VirtualCameras.create()
    private var cameraOpen = false
    private var cameraProblem: String? = null
    private var lastOpenAttempt = 0L

    private var composer: FrameComposer? = null
    private var nv12: ByteArray? = null
    private var closed = false

    private var framesInWindow = 0
    private var windowStart = 0L
    private var fps = 0
    private var lastStatus = 0L

    @Synchronized
    fun waiting() {
        if (!closed) onStatus("Waiting for phone camera", Tone.MUTED)
    }

    @Synchronized
    fun onFrame(image: BufferedImage, quarterTurns: Int, @Suppress("UNUSED_PARAMETER") front: Boolean) {
        if (closed) return
        val c = composer ?: FrameComposer(image.width, image.height).also {
            composer = it
            nv12 = ByteArray(it.width * it.height * 3 / 2)
            windowStart = System.currentTimeMillis()
            lastStatus = 0L
        }
        val canvas = c.compose(image, quarterTurns + userQuarterTurns, mirror)
        onPreview(canvas)

        val now = System.currentTimeMillis()
        if (camera != null && !cameraOpen && now - lastOpenAttempt >= OPEN_RETRY_MS) {
            lastOpenAttempt = now
            cameraProblem = camera.open(c.width, c.height, FPS)
            cameraOpen = cameraProblem == null
        }
        if (cameraOpen) {
            val buffer = nv12!!
            c.toNv12(canvas, buffer)
            try {
                camera!!.write(buffer)
            } catch (e: Exception) {
                cameraProblem = "Virtual camera stopped: ${e.message}"
                closeCamera()
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

    private fun reportLive(c: FrameComposer) {
        val size = "${c.width}×${c.height} · ${if (fps > 0) fps else FPS} fps"
        val problem = cameraProblem
        when {
            cameraOpen -> onStatus("$size → ${camera!!.label}", Tone.GOOD)
            camera == null -> onStatus("$size · preview only. Install OBS Studio for a webcam", Tone.WARN)
            problem == VirtualCameras.BUSY -> onStatus(problem, Tone.WARN)
            else -> onStatus("$size · preview only. ${problem ?: "Starting virtual camera"}", Tone.WARN)
        }
    }

    /** Phone video ended: release the webcam so apps show OBS's placeholder, not a frozen frame. */
    @Synchronized
    fun disconnected() {
        if (closed) return
        closeCamera()
        cameraProblem = null
        composer = null
        nv12 = null
        fps = 0
        onStatus("Waiting for phone camera", Tone.MUTED)
    }

    @Synchronized
    fun close() {
        closed = true
        closeCamera()
        composer = null
        nv12 = null
    }

    private fun closeCamera() {
        if (cameraOpen) {
            try { camera?.close() } catch (_: Exception) {}
        }
        cameraOpen = false
        lastOpenAttempt = 0L
    }
}
