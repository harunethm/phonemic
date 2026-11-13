package com.scylla.tool.phonemic

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams the phone camera to the desktop receiver as hardware-encoded H.264 over TCP,
 * alongside (not instead of) the UDP mic stream. The camera only runs while the desktop
 * has accepted our PIN; if the link stalls, queued frames are dropped and the encoder is
 * asked for a fresh keyframe, so latency stays bounded instead of growing.
 *
 * Wire format shared with PhoneMic-Desktop's VideoReceiver.kt - keep both in sync.
 * TCP to the same port NUMBER as the UDP audio. All integers big-endian.
 *   handshake         phone -> PC: 'P' 'M' 'V' '1' [pinLen u8][pin utf8]
 *                     PC -> phone: [status u8]  0 = OK, 1 = wrong PIN (PC then closes)
 *   FRAME             phone -> PC: [0x10][len u32][ptsMicros u64][flags u8][H.264 Annex-B bytes]
 *                       flags: bit0 keyframe, bits1-2 clockwise quarter turns to show it upright,
 *                       bit3 front camera. Keyframes carry SPS/PPS in front.
 *   KEYFRAME_REQUEST  PC -> phone: [0x20]
 *
 * [start], [stop] and [flip] must be called on the main thread.
 */
class CameraStreamer(
    private val context: Context,
    private val host: String,
    private val port: Int,
    private val pin: String,
    private var facing: CameraFacing,
    private val quality: CameraQuality,
    private val isAudioPaired: () -> Boolean,
    private val onState: (info: String?, error: String?) -> Unit
) : LifecycleOwner {

    private object Protocol {
        val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        const val STATUS_OK = 0
        const val STATUS_BAD_PIN = 1
        const val TYPE_FRAME: Byte = 0x10
        const val TYPE_KEYFRAME_REQUEST = 0x20
        const val FLAG_KEYFRAME = 0x01
        const val FLAG_FRONT = 0x08
        const val FRAME_HEADER_BYTES = 9 // ptsMicros u64 + flags u8
    }

    companion object {
        private const val FRAME_RATE = 30
        private const val I_FRAME_INTERVAL_S = 2
        private const val MAX_QUEUED_FRAMES = 15 // ~0.5 s: beyond this, drop and restart from a keyframe
        private const val CONNECT_TIMEOUT_MS = 3000
        private const val HANDSHAKE_TIMEOUT_MS = 3000
        private const val RETRY_MS = 2000L
        private const val RETRY_OLD_DESKTOP_MS = 5000L
        private const val KEYFRAME_MIN_INTERVAL_MS = 500L
        private const val REBIND_AFTER_ERROR_MS = 1000L
        private const val STALL_TIMEOUT_MS = 5000L
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null

    private val codecThread = HandlerThread("camera-encoder")
    private lateinit var codecHandler: Handler
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var encodedSize: Size? = null
    @Volatile private var rotationQuarterTurns = 0
    @Volatile private var frontCamera = facing == CameraFacing.FRONT
    private var lastKeyframeRequest = 0L

    // Main-thread only.
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var cameraBound = false
    private var targetRotation = Surface.ROTATION_0
    private var orientationListener: OrientationEventListener? = null

    private val queueLock = Object()
    private val queue = ArrayDeque<ByteArray>()
    private var waitingForKeyframe = true

    private val framesSent = AtomicInteger()
    private val bytesSent = AtomicLong()
    @Volatile private var lastWriteAt = 0L
    @Volatile private var error: String? = null

    fun start() {
        if (running) return
        running = true
        codecThread.start()
        codecHandler = Handler(codecThread.looper)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) = updateRotation(orientation)
        }.also { if (it.canDetectOrientation()) it.enable() }
        Thread({ connectLoop() }, "camera-connect").apply { isDaemon = true }.start()
        main.postDelayed(statsTick, 1000)
        onState("Camera: connecting to desktop…", null)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(statsTick)
        orientationListener?.disable()
        orientationListener = null
        try { socket?.close() } catch (_: Exception) {}
        synchronized(queueLock) {
            queue.clear()
            queueLock.notifyAll()
        }
        unbindCamera()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        // The encoder itself is released by CameraX's surface-result callback once it lets go.
        codecThread.quitSafely()
    }

    /** Switches between back and front camera while streaming. */
    fun flip() {
        facing = if (facing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        Prefs.setCameraFacing(context, facing)
        if (running && cameraBound) {
            unbindCamera()
            bindCamera()
        }
    }

    private fun setError(message: String?) {
        error = message
        if (running) onState(null, message)
    }

    // ---- Network ----

    private fun connectLoop() {
        while (running) {
            val s = Socket()
            var retryDelay = RETRY_MS
            try {
                s.tcpNoDelay = true
                // Small on purpose: a backlog should show up in our queue (where we can drop
                // frames), not hide in the kernel buffer as seconds of latency.
                s.sendBufferSize = 64 * 1024
                s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val pinBytes = pin.toByteArray(StandardCharsets.UTF_8)
                s.getOutputStream().write(Protocol.MAGIC + byteArrayOf(pinBytes.size.toByte()) + pinBytes)
                s.soTimeout = HANDSHAKE_TIMEOUT_MS
                val status = s.getInputStream().read()
                s.soTimeout = 0
                if (status == Protocol.STATUS_BAD_PIN) {
                    setError("Camera: pairing code rejected, re-pair with the desktop")
                    return
                }
                if (status != Protocol.STATUS_OK) throw IOException("Unexpected handshake reply $status")

                clearQueue()
                lastWriteAt = SystemClock.elapsedRealtime()
                socket = s
                setError(null)
                main.post { bindCamera() }
                Thread({ readLoop(s) }, "camera-reader").apply { isDaemon = true }.start()
                writeLoop(s)
            } catch (e: ConnectException) {
                // Audio already reaches the desktop, yet TCP is refused: an older desktop app.
                if (isAudioPaired() && e.message.orEmpty().contains("refused", ignoreCase = true)) {
                    setError("Desktop app doesn't support camera, update it")
                    retryDelay = RETRY_OLD_DESKTOP_MS
                } else if (isAudioPaired()) {
                    setError("Can't reach the desktop for camera. Allow Phone Mic (TCP) in its firewall")
                }
            } catch (_: SocketTimeoutException) {
                if (isAudioPaired()) setError("Can't reach the desktop for camera. Allow Phone Mic (TCP) in its firewall")
            } catch (_: Exception) {
                // connection dropped mid-stream; reconnect below
            } finally {
                if (socket === s) socket = null
                try { s.close() } catch (_: Exception) {}
                clearQueue()
                main.post { unbindCamera() }
            }
            sleepWhileRunning(retryDelay)
        }
    }

    private fun writeLoop(s: Socket) {
        val out = s.getOutputStream()
        while (running && socket === s) {
            val frame = synchronized(queueLock) {
                if (queue.isEmpty()) queueLock.wait(500)
                queue.removeFirstOrNull()
            } ?: continue
            out.write(frame)
            lastWriteAt = SystemClock.elapsedRealtime()
            framesSent.incrementAndGet()
            bytesSent.addAndGet(frame.size.toLong())
        }
    }

    private fun readLoop(s: Socket) {
        try {
            val input = s.getInputStream()
            while (running) {
                val b = input.read()
                if (b < 0) break
                if (b == Protocol.TYPE_KEYFRAME_REQUEST) requestKeyframe()
            }
        } catch (_: Exception) {
        }
        try { s.close() } catch (_: Exception) {} // also unblocks the writer
    }

    private fun sleepWhileRunning(ms: Long) {
        val end = SystemClock.elapsedRealtime() + ms
        while (running && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
    }

    private fun clearQueue() {
        synchronized(queueLock) {
            queue.clear()
            waitingForKeyframe = true
            queueLock.notifyAll()
        }
    }

    private fun enqueueFrame(data: ByteArray, ptsMicros: Long, keyframe: Boolean) {
        val flags = (if (keyframe) Protocol.FLAG_KEYFRAME else 0) or
            ((rotationQuarterTurns and 0x03) shl 1) or
            (if (frontCamera) Protocol.FLAG_FRONT else 0)
        val message = ByteArray(1 + 4 + Protocol.FRAME_HEADER_BYTES + data.size)
        ByteBuffer.wrap(message)
            .put(Protocol.TYPE_FRAME)
            .putInt(Protocol.FRAME_HEADER_BYTES + data.size)
            .putLong(ptsMicros)
            .put(flags.toByte())
            .put(data)

        var askForKeyframe = false
        synchronized(queueLock) {
            if (socket == null) return
            if (queue.size >= MAX_QUEUED_FRAMES) {
                // The link can't keep up: drop the backlog and resume at the next keyframe
                // (P-frames are useless without the frames they reference).
                queue.clear()
                waitingForKeyframe = true
                askForKeyframe = true
            }
            if (waitingForKeyframe && !keyframe) {
                askForKeyframe = true
            } else {
                waitingForKeyframe = false
                queue.addLast(message)
                queueLock.notifyAll()
            }
        }
        if (askForKeyframe) requestKeyframe()
    }

    private fun requestKeyframe() {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastKeyframeRequest < KEYFRAME_MIN_INTERVAL_MS) return
            lastKeyframeRequest = now
        }
        try {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {
            // codec being released; the next one starts with a keyframe anyway
        }
    }

    // ---- Camera (main thread) ----

    private fun bindCamera() {
        if (!running || cameraBound || socket == null) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!running || cameraBound || socket == null) return@addListener
            val provider = try {
                future.get()
            } catch (e: Exception) {
                setError("Camera unavailable: ${e.message}")
                return@addListener
            }
            cameraProvider = provider
            val selector = chooseCamera(provider) ?: run {
                setError("No camera available")
                return@addListener
            }
            val newPreview = Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(quality.width, quality.height),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setTargetFrameRate(Range(FRAME_RATE, FRAME_RATE))
                .setTargetRotation(targetRotation)
                .build()
            newPreview.setSurfaceProvider(mainExecutor) { request -> provideEncoderSurface(request) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, newPreview)
                preview = newPreview
                cameraBound = true
            } catch (e: Exception) {
                setError("Could not open the camera: ${e.message}")
            }
        }, mainExecutor)
    }

    private fun chooseCamera(provider: ProcessCameraProvider): CameraSelector? {
        fun has(selector: CameraSelector) = try { provider.hasCamera(selector) } catch (_: Exception) { false }
        val wantFront = facing == CameraFacing.FRONT
        val wanted = if (wantFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val other = if (wantFront) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        return when {
            has(wanted) -> wanted.also { frontCamera = wantFront }
            has(other) -> other.also { frontCamera = !wantFront }
            else -> null
        }
    }

    private fun unbindCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        preview = null
        cameraBound = false
    }

    private fun updateRotation(orientation: Int) {
        if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
        val rotation = when (orientation) {
            in 45 until 135 -> Surface.ROTATION_270
            in 135 until 225 -> Surface.ROTATION_180
            in 225 until 315 -> Surface.ROTATION_90
            else -> Surface.ROTATION_0
        }
        if (rotation != targetRotation) {
            targetRotation = rotation
            preview?.targetRotation = rotation
        }
    }

    /** CameraX asks for a surface to draw into: hand it a fresh encoder's input surface. */
    private fun provideEncoderSurface(request: SurfaceRequest) {
        val size = request.resolution
        val encoder: MediaCodec
        val surface: Surface
        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.setCallback(EncoderCallback(), codecHandler)
            try {
                encoder.configure(videoFormat(size, constantBitrate = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (_: Exception) {
                // Some encoders reject CBR; their default rate control is fine for this.
                encoder.configure(videoFormat(size, constantBitrate = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = encoder.createInputSurface()
            encoder.start()
        } catch (e: Exception) {
            setError("Camera encoder failed: ${e.message}")
            request.willNotProvideSurface()
            return
        }
        codec = encoder
        encodedSize = size
        // New encoder (start or flip): resume the stream at its first keyframe, never mixing in
        // leftover frames from the previous encoder.
        clearQueue()
        request.setTransformationInfoListener(mainExecutor) { info ->
            rotationQuarterTurns = (info.rotationDegrees / 90) and 0x03
        }
        request.provideSurface(surface, mainExecutor) {
            if (codec === encoder) codec = null
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            surface.release()
        }
    }

    private fun videoFormat(size: Size, constantBitrate: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, quality.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_S)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (constantBitrate) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            }
        }

    /** One per encoder, so a flip's new encoder never inherits the old one's SPS/PPS. */
    private inner class EncoderCallback : MediaCodec.Callback() {
        private var codecConfig: ByteArray? = null

        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            // Surface input: never called.
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = mc.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codecConfig = data
                    } else {
                        val keyframe = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val config = codecConfig
                        // Every keyframe carries SPS/PPS, so the desktop can (re)start decoding at any of them.
                        enqueueFrame(if (keyframe && config != null) config + data else data, info.presentationTimeUs, keyframe)
                    }
                }
                mc.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {
                // encoder released underneath us (flip / stop)
            }
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            // Some encoders only report SPS/PPS here instead of as a CODEC_CONFIG buffer.
            if (codecConfig != null) return
            val sps = format.getByteBuffer("csd-0") ?: return
            val pps = format.getByteBuffer("csd-1")
            val bytes = ByteArray(sps.remaining() + (pps?.remaining() ?: 0))
            sps.duplicate().get(bytes, 0, sps.remaining())
            pps?.duplicate()?.get(bytes, sps.remaining(), pps.remaining())
            codecConfig = bytes
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            setError("Camera encoder failed, restarting")
            main.post {
                if (!running) return@post
                unbindCamera()
                main.postDelayed({ bindCamera() }, REBIND_AFTER_ERROR_MS)
            }
        }
    }

    // ---- Status ----

    private val statsTick = object : Runnable {
        override fun run() {
            if (!running) return
            val frames = framesSent.getAndSet(0)
            val bytes = bytesSent.getAndSet(0)
            val connected = socket
            val size = encodedSize
            // TCP can't time out a blocked write: if nothing went out for a while although frames
            // are waiting, the desktop is gone (e.g. out of WiFi range) - drop the link and reconnect.
            val stalled = connected != null &&
                SystemClock.elapsedRealtime() - lastWriteAt > STALL_TIMEOUT_MS &&
                synchronized(queueLock) { queue.isNotEmpty() }
            if (stalled) try { connected?.close() } catch (_: Exception) {}
            val info = when {
                connected == null -> "Camera: connecting to desktop…"
                !cameraBound || size == null -> "Camera: starting…"
                else -> String.format(
                    Locale.getDefault(), // shown to the user, so their decimal separator
                    "Camera: %d×%d · %d fps · %.1f Mbit/s · %s",
                    size.width, size.height, frames, bytes * 8 / 1_000_000.0,
                    if (frontCamera) "front" else "back"
                )
            }
            onState(info, error)
            main.postDelayed(this, 1000)
        }
    }
}
