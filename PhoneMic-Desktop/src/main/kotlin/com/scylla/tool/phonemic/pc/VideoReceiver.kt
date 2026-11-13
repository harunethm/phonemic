package com.scylla.tool.phonemic.pc

import java.awt.image.BufferedImage
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Receives the phone's camera as H.264 over TCP and hands decoded frames to [onFrame].
 *
 * Wire format shared with PhoneMic-Android's CameraStreamer.kt - keep both in sync.
 * TCP, phone connects to the same port NUMBER as the UDP audio. All integers big-endian.
 *   handshake         phone -> PC: 'P' 'M' 'V' '1' [pinLen u8][pin utf8]
 *                     PC -> phone: [status u8]  0 = OK, 1 = wrong PIN (PC then closes)
 *   FRAME             phone -> PC: [0x10][len u32][ptsMicros u64][flags u8][H.264 Annex-B bytes]
 *                       flags: bit0 keyframe, bits1-2 clockwise quarter turns to show it upright,
 *                       bit3 front camera. Keyframes carry SPS/PPS in front.
 *   KEYFRAME_REQUEST  PC -> phone: [0x20]
 * End of video = socket close.
 *
 * Callbacks run on the receiver's own threads, never the EDT. [onFrame] runs inline with
 * decoding on purpose: a slow PC backs TCP up to the phone, which then drops frames.
 */
class VideoReceiver(
    private val port: Int,
    private val pinProvider: () -> String,
    private val onFrame: (image: BufferedImage, quarterTurns: Int, front: Boolean) -> Unit,
    private val onState: (connected: Boolean, address: String?) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte())
        const val STATUS_OK = 0
        const val STATUS_BAD_PIN = 1
        const val TYPE_FRAME = 0x10
        const val TYPE_KEYFRAME_REQUEST = 0x20
        const val FLAG_KEYFRAME = 0x01
        const val FLAG_FRONT = 0x08
        const val FRAME_HEADER_BYTES = 9 // ptsMicros u64 + flags u8
        private const val MAX_FRAME_BYTES = 4 * 1024 * 1024
        private const val HANDSHAKE_TIMEOUT_MS = 3000
        private const val KEYFRAME_REQUEST_INTERVAL_MS = 1000L

        /** Handshake bytes a phone sends; also used by the self-test. */
        fun handshake(pin: String): ByteArray {
            val pinBytes = pin.toByteArray(StandardCharsets.UTF_8)
            return MAGIC + byteArrayOf(pinBytes.size.coerceAtMost(255).toByte()) + pinBytes.copyOf(pinBytes.size.coerceAtMost(255))
        }
    }

    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null
    private val client = AtomicReference<Socket?>(null)
    private var acceptThread: Thread? = null
    @Volatile private var readerThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({ acceptLoop() }, "video-accept").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        dropClient()
        acceptThread?.join(500)
        readerThread?.join(1000)
        acceptThread = null
        readerThread = null
        server = null
    }

    /** Disconnects the current phone (e.g. after the pairing code was regenerated). */
    fun dropClient() {
        val current = client.getAndSet(null) ?: return
        try { current.close() } catch (_: Exception) {}
    }

    private fun acceptLoop() {
        val serverSocket = try {
            ServerSocket(port)
        } catch (e: Exception) {
            onError("Could not listen for camera on TCP port $port: ${e.message}")
            return
        }
        server = serverSocket
        if (!running.get()) {
            serverSocket.close()
            return
        }
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Exception) {
                break
            }
            if (!handshake(socket)) continue
            // A newly paired phone replaces the current one; closing the old socket ends its reader.
            client.getAndSet(socket)?.let { try { it.close() } catch (_: Exception) {} }
            readerThread = Thread({ readLoop(socket) }, "video-reader").also {
                it.isDaemon = true
                it.start()
            }
        }
        try { serverSocket.close() } catch (_: Exception) {}
    }

    private fun handshake(socket: Socket): Boolean {
        return try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val magic = ByteArray(MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MAGIC)) {
                socket.close()
                return false
            }
            val pinBytes = ByteArray(input.readUnsignedByte())
            input.readFully(pinBytes)
            val ok = String(pinBytes, StandardCharsets.UTF_8) == pinProvider()
            socket.getOutputStream().write(if (ok) STATUS_OK else STATUS_BAD_PIN)
            socket.getOutputStream().flush()
            if (!ok) {
                socket.close()
                return false
            }
            socket.soTimeout = 0
            true
        } catch (_: Exception) {
            try { socket.close() } catch (_: Exception) {}
            false
        }
    }

    private fun readLoop(socket: Socket) {
        val decoder = try {
            H264Decoder()
        } catch (e: Throwable) {
            onError("Video decoder unavailable: ${e.message}")
            try { socket.close() } catch (_: Exception) {}
            client.compareAndSet(socket, null)
            return
        }
        onState(true, socket.inetAddress.hostAddress)
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            val output = socket.getOutputStream()
            var buffer = ByteArray(256 * 1024)
            var waitingForKeyframe = true // nothing decodes before the first keyframe
            var lastKeyframeRequest = 0L

            while (running.get() && client.get() === socket) {
                val type = input.read()
                if (type < 0 || type != TYPE_FRAME) break // EOF or a protocol we don't speak
                val length = input.readInt()
                if (length < FRAME_HEADER_BYTES || length > MAX_FRAME_BYTES) break
                if (buffer.size < length) buffer = ByteArray(length)
                input.readFully(buffer, 0, length)

                val flags = buffer[8].toInt() and 0xFF
                val keyframe = flags and FLAG_KEYFRAME != 0
                if (waitingForKeyframe && !keyframe) continue
                waitingForKeyframe = false

                val image = decoder.decode(buffer, FRAME_HEADER_BYTES, length - FRAME_HEADER_BYTES)
                if (decoder.needsKeyframe) {
                    decoder.needsKeyframe = false
                    waitingForKeyframe = true
                    val now = System.currentTimeMillis()
                    if (now - lastKeyframeRequest >= KEYFRAME_REQUEST_INTERVAL_MS) {
                        output.write(TYPE_KEYFRAME_REQUEST)
                        output.flush()
                        lastKeyframeRequest = now
                    }
                    continue
                }
                if (image != null) onFrame(image, (flags shr 1) and 0x03, flags and FLAG_FRONT != 0)
            }
        } catch (_: Exception) {
            // socket closed / reset - handled below like a normal end of stream
        } finally {
            decoder.close()
            try { socket.close() } catch (_: Exception) {}
            // Only report the disconnect if a newer phone hasn't already taken over.
            if (client.compareAndSet(socket, null)) onState(false, null)
        }
    }
}
