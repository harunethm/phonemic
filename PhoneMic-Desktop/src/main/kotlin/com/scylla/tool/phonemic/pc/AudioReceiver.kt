package com.scylla.tool.phonemic.pc

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine

/**
 * Listens for the PCM/UDP stream sent by the Phone Mic Android app and plays it into a
 * chosen output mixer (typically VB-Audio Virtual Cable's "CABLE Input"), so it shows up
 * system-wide as a microphone via "CABLE Output".
 *
 * Wire format shared with PhoneMic-Android's MicStreamService.kt - keep both in sync:
 *   HELLO      (phone -> PC): [0x01][pinLen][pin utf8 bytes]
 *   HELLO_ACK  (PC -> phone): [0x02]
 *   AUDIO      (phone -> PC): [0x03][seq BE32][pcm16 le]
 *   QUALITY    (PC -> phone): [0x04][lossPercent 0-100]
 *   BYE        (phone -> PC): [0x05]  (phone stopped streaming)
 */
class AudioReceiver(
    private val port: Int,
    private val mixerInfo: Mixer.Info,
    initialPin: String,
    private val onStats: (received: Long, lost: Long, level: Float) -> Unit,
    private val onPaired: (senderAddress: String?) -> Unit,
    private val onError: (String) -> Unit,
    private val onRemoteStop: () -> Unit
) {
    companion object {
        // Must match the Android app's hardcoded capture format exactly.
        val FORMAT = AudioFormat(48000f, 16, 1, true, false)
        private const val MAX_PACKET_SIZE = 2048
        private const val AUDIO_HEADER_BYTES = 5
        private const val REORDER_WINDOW = 4
        private const val QUALITY_INTERVAL_MS = 1000L

        private const val TYPE_HELLO: Byte = 0x01
        private const val TYPE_HELLO_ACK: Byte = 0x02
        private const val TYPE_AUDIO: Byte = 0x03
        private const val TYPE_QUALITY: Byte = 0x04
        private const val TYPE_BYE: Byte = 0x05

        /** Random pairing code shown as the QR payload's third field and the numeric fallback. */
        fun generatePin(): String {
            val random = SecureRandom()
            val a = random.nextInt(10000)
            val b = random.nextInt(10000)
            return "%04d-%04d".format(a, b)
        }
    }

    @Volatile var gain: Float = 1f
    @Volatile var pin: String = initialPin
        private set

    @Volatile private var authorizedAddress: InetSocketAddress? = null

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null
    private var line: SourceDataLine? = null

    /** Regenerates the pairing PIN and revokes any currently-authorized sender. */
    fun regeneratePin(): String {
        val newPin = generatePin()
        pin = newPin
        authorizedAddress = null
        onPaired(null)
        return newPin
    }

    fun start() {
        if (running.get()) return
        running.set(true)
        thread = Thread { runLoop() }.also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        thread?.join(500)
        try { line?.drain() } catch (_: Exception) {}
        try { line?.stop() } catch (_: Exception) {}
        try { line?.close() } catch (_: Exception) {}
        line = null
        socket = null
        thread = null
        authorizedAddress = null
    }

    private fun runLoop() {
        val mixer = AudioSystem.getMixer(mixerInfo)
        val lineInfo = DataLine.Info(SourceDataLine::class.java, FORMAT)
        if (!mixer.isLineSupported(lineInfo)) {
            onError("Selected device does not support the required audio format")
            return
        }

        val sourceLine: SourceDataLine
        try {
            sourceLine = mixer.getLine(lineInfo) as SourceDataLine
            sourceLine.open(FORMAT)
            sourceLine.start()
        } catch (e: LineUnavailableException) {
            onError("Could not open audio device: ${e.message}")
            return
        }
        line = sourceLine

        val udpSocket: DatagramSocket
        try {
            udpSocket = DatagramSocket(port)
        } catch (e: SocketException) {
            onError("Could not listen on port $port: ${e.message}")
            return
        }
        socket = udpSocket

        val buffer = ByteArray(MAX_PACKET_SIZE)
        var received = 0L
        var totalLost = 0L
        var lastStatsUpdate = 0L
        var lastQualitySent = 0L
        var peakSinceUpdate = 0

        var nextPlaySeq: Int? = null
        val pending = HashMap<Int, ByteArray>()
        var lastPlayedFrame: ByteArray? = null

        var windowReceived = 0
        var windowLost = 0

        // Applies gain to a *copy* of the raw payload and writes that; `lastPlayedFrame`
        // always holds the pre-gain bytes so repeated concealment can't compound gain
        // on top of gain across consecutive lost frames.
        fun applyGainAndWrite(rawPayload: ByteArray) {
            val currentGain = gain
            val out = rawPayload.copyOf()
            var i = 0
            var peak = 0
            while (i + 1 < out.size) {
                val sample = ((out[i + 1].toInt() shl 8) or (out[i].toInt() and 0xFF)).toShort()
                val boosted = (sample * currentGain).toInt().coerceIn(-32768, 32767)
                out[i] = (boosted and 0xFF).toByte()
                out[i + 1] = ((boosted shr 8) and 0xFF).toByte()
                val abs = kotlin.math.abs(boosted)
                if (abs > peak) peak = abs
                i += 2
            }
            sourceLine.write(out, 0, out.size)
            if (peak > peakSinceUpdate) peakSinceUpdate = peak
            lastPlayedFrame = rawPayload
        }

        fun drain() {
            var next = nextPlaySeq ?: return
            while (pending.containsKey(next)) {
                applyGainAndWrite(pending.remove(next)!!)
                next++
            }
            // A gap that hasn't resolved within the reorder window is a real loss, not
            // just reordering - conceal it (repeat the last frame) and move past it so
            // the buffer can't grow without bound while waiting for a packet that's gone.
            while (pending.size > REORDER_WINDOW) {
                lastPlayedFrame?.let { applyGainAndWrite(it) }
                totalLost++
                windowLost++
                next++
                while (pending.containsKey(next)) {
                    applyGainAndWrite(pending.remove(next)!!)
                    next++
                }
            }
            nextPlaySeq = next
        }

        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                udpSocket.receive(packet)
            } catch (e: Exception) {
                if (running.get()) onError("Receive error: ${e.message}")
                break
            }

            if (packet.length < 1) continue
            val senderAddress = InetSocketAddress(packet.address, packet.port)

            when (buffer[0]) {
                TYPE_HELLO -> {
                    if (packet.length < 2) continue
                    val pinLen = buffer[1].toInt() and 0xFF
                    if (packet.length < 2 + pinLen) continue
                    val receivedPin = String(buffer, 2, pinLen, StandardCharsets.UTF_8)
                    if (receivedPin == pin) {
                        authorizedAddress = senderAddress
                        // New phone session restarts seq at 0; drop old ordering state or every packet looks "late".
                        nextPlaySeq = null
                        pending.clear()
                        onPaired(senderAddress.address.hostAddress)
                        val ack = byteArrayOf(TYPE_HELLO_ACK)
                        try {
                            udpSocket.send(DatagramPacket(ack, ack.size, packet.address, packet.port))
                        } catch (_: Exception) {
                        }
                    }
                }
                TYPE_AUDIO -> {
                    if (authorizedAddress != senderAddress) continue
                    if (packet.length < AUDIO_HEADER_BYTES) continue
                    val seq = ((buffer[1].toInt() and 0xFF) shl 24) or
                        ((buffer[2].toInt() and 0xFF) shl 16) or
                        ((buffer[3].toInt() and 0xFF) shl 8) or
                        (buffer[4].toInt() and 0xFF)
                    val payload = buffer.copyOfRange(AUDIO_HEADER_BYTES, packet.length)

                    if (nextPlaySeq == null) nextPlaySeq = seq
                    // ponytail: plain Int seq comparison, no wraparound handling - a session
                    // would need ~248 days of continuous streaming to wrap, not a real case.
                    if (seq >= nextPlaySeq!!) {
                        pending[seq] = payload
                    } // else: arrived after we already concealed/played past this seq, drop it
                    received++
                    windowReceived++
                    drain()
                }
                TYPE_BYE -> {
                    if (authorizedAddress == senderAddress) {
                        authorizedAddress = null
                        nextPlaySeq = null
                        pending.clear()
                        onPaired(null)
                        onRemoteStop()
                    }
                }
                else -> { /* unknown control packet, ignore */ }
            }

            val now = System.currentTimeMillis()
            if (now - lastStatsUpdate > 200) {
                onStats(received, totalLost, (peakSinceUpdate / 32768f).coerceIn(0f, 1f))
                peakSinceUpdate = 0
                lastStatsUpdate = now
            }
            val authorized = authorizedAddress
            if (authorized != null && now - lastQualitySent > QUALITY_INTERVAL_MS) {
                val total = windowReceived + windowLost
                val lossPercent = if (total > 0) (windowLost * 100 / total).coerceIn(0, 100) else 0
                val qualityPacket = byteArrayOf(TYPE_QUALITY, lossPercent.toByte())
                try {
                    udpSocket.send(
                        DatagramPacket(qualityPacket, qualityPacket.size, authorized.address, authorized.port)
                    )
                } catch (_: Exception) {
                }
                windowReceived = 0
                windowLost = 0
                lastQualitySent = now
            }
        }

        onStats(received, totalLost, 0f)
    }
}
