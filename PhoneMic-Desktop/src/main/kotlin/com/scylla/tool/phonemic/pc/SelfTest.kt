package com.scylla.tool.phonemic.pc

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Developer checks for the camera path that don't need a phone:
 *   --video-selftest  encode a synthetic clip with FFmpeg, stream it through VideoReceiver over
 *                     loopback TCP using the real protocol, count decoded frames; also checks
 *                     that a wrong PIN is rejected. Exit code 0 = PASS (or SKIP), 1 = FAIL.
 *   --vcam-test       open the virtual camera and show moving colour bars for 60 s.
 */
object SelfTest {
    private const val WIDTH = 640
    private const val HEIGHT = 360
    private const val FRAMES = 90

    fun runVideoSelfTest(): Int {
        val clip = encodeTestClip()
        if (clip == null) {
            println("SKIP: this FFmpeg build has no usable H.264 encoder")
            return 0
        }
        println("Encoded ${clip.size} test frames (${clip.count { it.second }} keyframes)")

        val port = ServerSocket(0).use { it.localPort }
        val pin = "1234-5678"
        val decoded = AtomicInteger()
        val wrongSize = AtomicInteger()
        val receiver = VideoReceiver(
            port = port,
            pinProvider = { pin },
            onFrame = { image, _, _ ->
                if (image.width == WIDTH && image.height == HEIGHT) decoded.incrementAndGet() else wrongSize.incrementAndGet()
            },
            onState = { connected, address -> println("video ${if (connected) "connected from $address" else "disconnected"}") },
            onError = { println("receiver error: $it") }
        )
        receiver.start()
        Thread.sleep(300)

        val badPinStatus = try {
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 3000
                s.getOutputStream().write(VideoReceiver.handshake("0000-0000"))
                s.getInputStream().read()
            }
        } catch (e: Exception) {
            println("wrong-PIN connection failed: ${e.message}")
            -1
        }

        val goodPinStatus = try {
            Socket("127.0.0.1", port).use { s ->
                s.tcpNoDelay = true
                s.soTimeout = 3000
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                out.write(VideoReceiver.handshake(pin))
                out.flush()
                val status = s.getInputStream().read()
                if (status == VideoReceiver.STATUS_OK) {
                    clip.forEachIndexed { i, (bytes, key) ->
                        out.writeByte(VideoReceiver.TYPE_FRAME)
                        out.writeInt(VideoReceiver.FRAME_HEADER_BYTES + bytes.size)
                        out.writeLong(i * 33_333L)
                        out.writeByte(if (key) VideoReceiver.FLAG_KEYFRAME else 0)
                        out.write(bytes)
                    }
                    out.flush()
                    // Let the receiver drain before closing the socket.
                    val deadline = System.currentTimeMillis() + 5000
                    while (decoded.get() + wrongSize.get() < clip.size && System.currentTimeMillis() < deadline) Thread.sleep(50)
                }
                status
            }
        } catch (e: Exception) {
            println("streaming connection failed: ${e.message}")
            -1
        }
        receiver.stop()

        val pass = badPinStatus == VideoReceiver.STATUS_BAD_PIN && goodPinStatus == VideoReceiver.STATUS_OK &&
            decoded.get() >= 80 && wrongSize.get() == 0
        println("wrong PIN status=$badPinStatus (want 1), right PIN status=$goodPinStatus (want 0)")
        println("decoded ${decoded.get()}/${clip.size} frames at ${WIDTH}x$HEIGHT, ${wrongSize.get()} at another size")
        println(if (pass) "PASS" else "FAIL")
        return if (pass) 0 else 1
    }

    /** Synthetic moving-gradient clip as (Annex-B access unit, isKeyframe), or null without an encoder. */
    private fun encodeTestClip(): List<Pair<ByteArray, Boolean>>? {
        for (name in listOf("libopenh264", "h264_mf", "h264_videotoolbox")) {
            val codec: AVCodec = avcodec.avcodec_find_encoder_by_name(name) ?: continue
            if (codec.isNull) continue
            encodeWith(codec)?.let {
                println("Test clip encoder: $name")
                return it
            }
        }
        return null
    }

    private fun encodeWith(codec: AVCodec): List<Pair<ByteArray, Boolean>>? {
        val ctx = avcodec.avcodec_alloc_context3(codec) ?: return null
        ctx.width(WIDTH)
        ctx.height(HEIGHT)
        ctx.time_base(avutil.av_make_q(1, 30))
        ctx.framerate(avutil.av_make_q(30, 1))
        ctx.pix_fmt(avutil.AV_PIX_FMT_YUV420P)
        ctx.bit_rate(1_000_000)
        ctx.gop_size(30)
        ctx.max_b_frames(0)
        if (avcodec.avcodec_open2(ctx, codec, null as AVDictionary?) < 0) {
            avcodec.avcodec_free_context(ctx)
            return null
        }
        val frame = avutil.av_frame_alloc()
        frame.format(avutil.AV_PIX_FMT_YUV420P)
        frame.width(WIDTH)
        frame.height(HEIGHT)
        avutil.av_frame_get_buffer(frame, 32)
        val packet = avcodec.av_packet_alloc()
        val out = ArrayList<Pair<ByteArray, Boolean>>()

        fun drain() {
            while (avcodec.avcodec_receive_packet(ctx, packet) >= 0) {
                val bytes = ByteArray(packet.size())
                packet.data().get(bytes)
                out += bytes to (packet.flags() and avcodec.AV_PKT_FLAG_KEY != 0)
                avcodec.av_packet_unref(packet)
            }
        }

        val row = ByteArray(WIDTH)
        for (i in 0 until FRAMES) {
            avutil.av_frame_make_writable(frame)
            val yStride = frame.linesize(0)
            for (y in 0 until HEIGHT) {
                for (x in 0 until WIDTH) row[x] = ((x + y + i * 4) and 0xFF).toByte()
                frame.data(0).position(y.toLong() * yStride).put(row, 0, WIDTH)
            }
            for (plane in 1..2) {
                val stride = frame.linesize(plane)
                row.fill((96 + plane * 32 + i).toByte())
                for (y in 0 until HEIGHT / 2) frame.data(plane).position(y.toLong() * stride).put(row, 0, WIDTH / 2)
            }
            frame.pts(i.toLong())
            if (avcodec.avcodec_send_frame(ctx, frame) < 0) break
            drain()
        }
        avcodec.avcodec_send_frame(ctx, null as AVFrame?)
        drain()

        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
        avcodec.avcodec_free_context(ctx)
        return out.takeIf { it.size >= FRAMES * 9 / 10 && it.first().second }
    }

    fun runVirtualCameraTest(): Int {
        val camera = VirtualCameras.create()
        if (camera == null) {
            println("No virtual camera available. Install OBS Studio (macOS: 30+, plus the phonemic-vcam helper).")
            return 1
        }
        val composer = FrameComposer(1280, 720)
        val error = camera.open(composer.width, composer.height, 30)
        if (error != null) {
            println("Could not open ${camera.label}: $error")
            return 1
        }
        println("Opened ${camera.label} at ${composer.width}x${composer.height}. Showing colour bars for 60 s...")
        val bars = arrayOf(Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN, Color.MAGENTA, Color.RED, Color.BLUE)
        val source = BufferedImage(composer.width, composer.height, BufferedImage.TYPE_INT_RGB)
        val nv12 = ByteArray(composer.width * composer.height * 3 / 2)
        val start = System.currentTimeMillis()
        try {
            var frame = 0
            while (System.currentTimeMillis() - start < 60_000) {
                val g = source.createGraphics()
                val barWidth = composer.width / bars.size
                bars.forEachIndexed { i, color ->
                    g.color = color
                    g.fillRect(((i * barWidth + frame * 4) % composer.width), 0, barWidth, composer.height)
                }
                g.color = Color.BLACK
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 48)
                g.drawString("Phone Mic camera test  ${frame / 30}s", 40, composer.height - 40)
                g.dispose()
                composer.toNv12(composer.compose(source, 0, false), nv12)
                camera.write(nv12)
                frame++
                Thread.sleep(33)
            }
        } finally {
            camera.close()
        }
        println("Done.")
        return 0
    }
}
