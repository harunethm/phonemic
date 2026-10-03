package com.scylla.tool.phonemic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/**
 * Foreground service that captures the phone's microphone and streams it as raw
 * PCM over UDP to a PC on the same WiFi network. No compression/ack layer for the
 * audio path by design - for real-time voice during gaming, a dropped 10ms frame
 * is inaudible but waiting on a retransmit is not. A separate lightweight control
 * channel (same socket, [Protocol.TYPE_HELLO]/[Protocol.TYPE_QUALITY]) handles
 * pairing and connection-quality feedback, since those need to be reliable-ish.
 */
class MicStreamService : Service() {

    enum class Quality { UNKNOWN, EXCELLENT, GOOD, POOR, LOST }

    data class StreamState(
        val isStreaming: Boolean = false,
        val host: String = "",
        val port: Int = 0,
        val packetsSent: Long = 0,
        val level: Float = 0f, // 0..1 peak amplitude of the most recent chunk(s)
        val paired: Boolean = false,
        val quality: Quality = Quality.UNKNOWN,
        val error: String? = null
    )

    /** Wire format shared with PhoneMic-Desktop's AudioReceiver.kt - keep both in sync. */
    private object Protocol {
        const val TYPE_HELLO: Byte = 0x01      // phone -> PC: [type][pinLen][pin utf8 bytes]
        const val TYPE_HELLO_ACK: Byte = 0x02  // PC -> phone: [type]
        const val TYPE_AUDIO: Byte = 0x03      // phone -> PC: [type][seq BE32][pcm16 le]
        const val TYPE_QUALITY: Byte = 0x04    // PC -> phone: [type][lossPercent 0-100]
        const val TYPE_BYE: Byte = 0x05        // phone -> PC: [type]
    }

    companion object {
        const val ACTION_START = "com.scylla.tool.phonemic.action.START"
        const val ACTION_STOP = "com.scylla.tool.phonemic.action.STOP"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_PIN = "extra_pin"

        private const val CHANNEL_ID = "phone_mic_stream"
        private const val NOTIFICATION_ID = 1

        private const val SAMPLE_RATE = 48000
        private const val CHUNK_SAMPLES = 480 // 10ms @ 48kHz, keeps each UDP packet well under the MTU
        private const val CHUNK_BYTES = CHUNK_SAMPLES * 2 // 16-bit PCM
        private const val AUDIO_HEADER_BYTES = 5 // 1 type byte + 4 seq bytes

        private const val HELLO_RETRY_MS = 2000L
        private const val QUALITY_TIMEOUT_MS = 3000L
        private const val HIGH_PASS_CUTOFF_HZ = 80.0

        val status = MutableStateFlow(StreamState())
    }

    @Volatile private var running = false
    private var captureThread: Thread? = null
    private var controlThread: Thread? = null
    @Volatile private var socket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var monitorTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    @Volatile private var paired = false
    @Volatile private var quality = Quality.UNKNOWN
    @Volatile private var lastQualityAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
                startStreaming(host, port, pin)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        captureThread?.join(500)
        captureThread = null
        controlThread?.join(500)
        controlThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        audioRecord?.release()
        audioRecord = null
        releaseEffects()
        try {
            monitorTrack?.stop()
        } catch (_: Exception) {
        }
        monitorTrack?.release()
        monitorTrack = null
        socket?.close()
        socket = null
        status.value = StreamState()
        super.onDestroy()
    }

    private fun releaseEffects() {
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { automaticGainControl?.release() } catch (_: Exception) {}
        try { echoCanceler?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        automaticGainControl = null
        echoCanceler = null
    }

    private fun startStreaming(host: String, port: Int, pin: String) {
        if (running) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            status.value = StreamState(error = "Microphone permission not granted")
            stopSelf()
            return
        }

        createNotificationChannel()
        val notification = buildNotification(host, port)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val scenario = Prefs.getScenario(this)
        val noiseReductionEnabled = Prefs.isNoiseReductionEnabled(this)
        val monitoringEnabled = Prefs.isMonitoringEnabled(this)

        captureThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            runCaptureLoop(host, port, pin, scenario, noiseReductionEnabled, monitoringEnabled)
        }.also { it.start() }
    }

    private fun audioSourceFor(scenario: CaptureScenario): Int = when (scenario) {
        CaptureScenario.SPEAKER_NEARBY, CaptureScenario.DISTANT_VOICE ->
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        // Music shouldn't go through voice-tuned DSP (AGC/NS mangle instruments/dynamics);
        // UNPROCESSED bypasses the vendor's voice pipeline. Requires API 24, matches minSdk.
        CaptureScenario.MUSIC -> MediaRecorder.AudioSource.UNPROCESSED
    }

    private fun softwareGainFor(scenario: CaptureScenario): Float = when (scenario) {
        CaptureScenario.SPEAKER_NEARBY -> 1.0f
        CaptureScenario.DISTANT_VOICE -> 1.8f
        CaptureScenario.MUSIC -> 1.0f
    }

    @Suppress("MissingPermission") // checked in startStreaming before this thread is launched
    private fun runCaptureLoop(
        host: String,
        port: Int,
        pin: String,
        scenario: CaptureScenario,
        noiseReductionEnabled: Boolean,
        monitoringEnabled: Boolean
    ) {
        val address: InetAddress
        try {
            address = InetAddress.getByName(host)
        } catch (e: Exception) {
            status.value = StreamState(error = "Could not resolve address: ${e.message}")
            stopSelf()
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            status.value = StreamState(error = "This device does not support the required audio format")
            stopSelf()
            return
        }
        val bufferSize = maxOf(minBuf, CHUNK_BYTES * 4)

        val record = AudioRecord(
            audioSourceFor(scenario),
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            status.value = StreamState(error = "Failed to initialize the microphone")
            record.release()
            stopSelf()
            return
        }
        audioRecord = record

        // Noise suppression/AGC only make sense for voice scenarios - Music intentionally
        // uses UNPROCESSED capture and skips them to avoid mangling non-voice audio.
        if (noiseReductionEnabled && scenario != CaptureScenario.MUSIC) {
            val sessionId = record.audioSessionId
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.setEnabled(true) }
            }
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId)?.also { it.setEnabled(true) }
            }
        }
        // Echo canceler is only relevant when monitoring plays the mic back through this
        // phone's own speaker/output - that's the only "far end" this app ever creates.
        if (monitoringEnabled && AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.also { it.setEnabled(true) }
        }

        val monitor = if (monitoringEnabled) createMonitorTrack() else null
        monitorTrack = monitor

        var udpSocket = DatagramSocket()
        socket = udpSocket

        record.startRecording()
        running = true
        paired = false
        quality = Quality.UNKNOWN
        lastQualityAt = 0L
        status.value = StreamState(isStreaming = true, host = host, port = port)

        controlThread = Thread { runControlLoop() }.also { it.start() }

        val highPass = HighPassFilter(SAMPLE_RATE.toDouble(), HIGH_PASS_CUTOFF_HZ)
        val gain = softwareGainFor(scenario)
        val pcmBuffer = ShortArray(CHUNK_SAMPLES)
        val packet = ByteArray(AUDIO_HEADER_BYTES + CHUNK_BYTES)
        packet[0] = Protocol.TYPE_AUDIO
        var seq = 0
        var sent = 0L
        var lastUiUpdate = 0L
        var lastHelloSent = 0L
        var peakSinceUpdate = 0
        var consecutiveSendFailures = 0

        sendHello(udpSocket, address, port, pin)
        lastHelloSent = System.currentTimeMillis()

        while (running) {
            val read = record.read(pcmBuffer, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING)
            if (read <= 0) continue

            for (i in 0 until read) {
                val filtered = highPass.process(pcmBuffer[i].toDouble())
                val boosted = (filtered * gain).toInt().coerceIn(-32768, 32767)
                pcmBuffer[i] = boosted.toShort()
                val abs = kotlin.math.abs(boosted)
                if (abs > peakSinceUpdate) peakSinceUpdate = abs
            }

            monitor?.write(pcmBuffer, 0, read)

            packet[1] = (seq ushr 24).toByte()
            packet[2] = (seq ushr 16).toByte()
            packet[3] = (seq ushr 8).toByte()
            packet[4] = seq.toByte()

            var o = AUDIO_HEADER_BYTES
            for (i in 0 until read) {
                val s = pcmBuffer[i].toInt()
                packet[o] = (s and 0xFF).toByte()
                packet[o + 1] = ((s shr 8) and 0xFF).toByte()
                o += 2
            }

            try {
                udpSocket.send(DatagramPacket(packet, AUDIO_HEADER_BYTES + read * 2, address, port))
                consecutiveSendFailures = 0
            } catch (e: Exception) {
                // Network reliability gap fix: don't die on a transient send failure (e.g. a
                // brief WiFi drop). Keep the mic capture running and keep retrying; only
                // recreate the socket after repeated failures, since a fresh socket rarely
                // helps a routing-level "network unreachable" on its own.
                consecutiveSendFailures++
                status.value = status.value.copy(error = "Send failed, retrying: ${e.message}")
                if (consecutiveSendFailures >= 10) {
                    try { udpSocket.close() } catch (_: Exception) {}
                    try {
                        udpSocket = DatagramSocket()
                        socket = udpSocket
                    } catch (_: Exception) {
                        // will retry again on the next failed send
                    }
                    consecutiveSendFailures = 0
                }
                Thread.sleep(minOf(200L * consecutiveSendFailures, 1000L))
                continue
            }

            seq++
            sent++
            val now = System.currentTimeMillis()

            if (!paired && now - lastHelloSent > HELLO_RETRY_MS) {
                sendHello(udpSocket, address, port, pin)
                lastHelloSent = now
            }
            if (paired && quality != Quality.LOST && now - lastQualityAt > QUALITY_TIMEOUT_MS) {
                quality = Quality.LOST
            }

            if (now - lastUiUpdate > 200) {
                status.value = status.value.copy(
                    packetsSent = sent,
                    level = (peakSinceUpdate / 32768f).coerceIn(0f, 1f),
                    paired = paired,
                    quality = quality,
                    error = null
                )
                peakSinceUpdate = 0
                lastUiUpdate = now
            }
        }

        status.value = status.value.copy(packetsSent = sent, isStreaming = false)
        try {
            record.stop()
        } catch (_: Exception) {
        }
        record.release()
        // Tell the PC we're done so it goes back to "not paired"; best-effort, UDP.
        try {
            udpSocket.send(DatagramPacket(byteArrayOf(Protocol.TYPE_BYE), 1, address, port))
        } catch (_: Exception) {
        }
        udpSocket.close()
    }

    private fun sendHello(socket: DatagramSocket, address: InetAddress, port: Int, pin: String) {
        val pinBytes = pin.toByteArray(StandardCharsets.UTF_8)
        val packet = ByteArray(2 + pinBytes.size)
        packet[0] = Protocol.TYPE_HELLO
        packet[1] = pinBytes.size.coerceIn(0, 255).toByte()
        System.arraycopy(pinBytes, 0, packet, 2, pinBytes.size)
        try {
            socket.send(DatagramPacket(packet, packet.size, address, port))
        } catch (_: Exception) {
            // best-effort; will retry on the next HELLO_RETRY_MS tick
        }
    }

    private fun runControlLoop() {
        val buffer = ByteArray(16)
        while (running) {
            val s = socket
            if (s == null) {
                Thread.sleep(100)
                continue
            }
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                s.receive(packet)
                if (packet.length < 1) continue
                when (buffer[0]) {
                    Protocol.TYPE_HELLO_ACK -> paired = true
                    Protocol.TYPE_QUALITY -> {
                        if (packet.length >= 2) {
                            paired = true
                            lastQualityAt = System.currentTimeMillis()
                            val lossPercent = buffer[1].toInt() and 0xFF
                            quality = when {
                                lossPercent <= 1 -> Quality.EXCELLENT
                                lossPercent <= 5 -> Quality.GOOD
                                else -> Quality.POOR
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                Thread.sleep(100)
            }
        }
    }

    private fun createMonitorTrack(): AudioTrack? {
        return try {
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        } catch (_: Exception) {
            null
        }
    }

    /** One-pole IIR high-pass, removes handling/rumble noise below [cutoffHz] before send. */
    private class HighPassFilter(sampleRate: Double, cutoffHz: Double) {
        private val alpha: Double
        private var prevIn = 0.0
        private var prevOut = 0.0

        init {
            val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
            val dt = 1.0 / sampleRate
            alpha = rc / (rc + dt)
        }

        fun process(sample: Double): Double {
            val out = alpha * (prevOut + sample - prevIn)
            prevIn = sample
            prevOut = out
            return out
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(host: String, port: Int): Notification {
        val stopIntent = Intent(this, MicStreamService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Streaming to $host:$port")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.notification_stop_action), stopPendingIntent)
            .build()
    }
}
