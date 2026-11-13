package com.scylla.tool.phonemic.pc

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swscale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.PointerPointer
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.nio.ByteOrder

/**
 * Decodes the phone's H.264 (Annex-B) frames with FFmpeg into a reused RGB [BufferedImage].
 * Single-threaded + LOW_DELAY so every packet in yields its picture out immediately - frame
 * threading would add a frame of latency per decoder thread.
 */
class H264Decoder : AutoCloseable {
    private val context: AVCodecContext
    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    private var sws: SwsContext? = null
    private var image: BufferedImage? = null
    private var rgb: BytePointer? = null
    private val rgbPlanes = PointerPointer<BytePointer>(1L)
    private val rgbStride = IntPointer(1L)

    /** Set when the stream became undecodable; the caller should skip to the next keyframe (and ask for one). */
    var needsKeyframe = false

    init {
        avutil.av_log_set_level(avutil.AV_LOG_QUIET)
        val codec = avcodec.avcodec_find_decoder(avcodec.AV_CODEC_ID_H264)
        check(codec != null && !codec.isNull) { "FFmpeg has no H.264 decoder" }
        context = avcodec.avcodec_alloc_context3(codec)
        context.thread_count(1)
        context.flags(context.flags() or avcodec.AV_CODEC_FLAG_LOW_DELAY)
        val opened = avcodec.avcodec_open2(context, codec, null as AVDictionary?)
        if (opened < 0) {
            close()
            throw IllegalStateException("Could not open the H.264 decoder ($opened)")
        }
    }

    /** Feeds one access unit; returns the decoded picture, or null if none is ready / on error. */
    fun decode(data: ByteArray, offset: Int, length: Int): BufferedImage? {
        if (avcodec.av_new_packet(packet, length) < 0) return null
        packet.data().put(data, offset, length)
        val sent = avcodec.avcodec_send_packet(context, packet)
        avcodec.av_packet_unref(packet)
        if (sent < 0 && sent != avutil.AVERROR_EAGAIN()) return fail()

        var result: BufferedImage? = null
        while (true) {
            val received = avcodec.avcodec_receive_frame(context, frame)
            if (received == avutil.AVERROR_EAGAIN() || received == avutil.AVERROR_EOF) break
            if (received < 0) return fail()
            result = toImage(frame)
            avutil.av_frame_unref(frame)
        }
        return result
    }

    private fun fail(): BufferedImage? {
        avcodec.avcodec_flush_buffers(context)
        needsKeyframe = true
        return null
    }

    private fun toImage(f: AVFrame): BufferedImage? {
        val w = f.width()
        val h = f.height()
        if (w <= 0 || h <= 0) return null
        var img = image
        if (img == null || img.width != w || img.height != h) {
            img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            image = img
            rgb?.close()
            val buffer = BytePointer(w.toLong() * h * 4)
            rgb = buffer
            rgbPlanes.put(0L, buffer)
            rgbStride.put(0L, w * 4)
        }
        val scaler = swscale.sws_getCachedContext(
            sws, w, h, f.format(), w, h, avutil.AV_PIX_FMT_BGRA, swscale.SWS_BILINEAR,
            null, null, null as DoublePointer?
        ) ?: return null
        sws = scaler
        swscale.sws_scale(scaler, f.data(), f.linesize(), 0, h, rgbPlanes, rgbStride)
        // BGRA bytes read as little-endian ints are 0xAARRGGBB - exactly TYPE_INT_RGB's layout (alpha ignored).
        val pixels = (img.raster.dataBuffer as DataBufferInt).data
        rgb!!.position(0L).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels, 0, w * h)
        return img
    }

    override fun close() {
        try { avutil.av_frame_free(frame) } catch (_: Throwable) {}
        try { avcodec.av_packet_free(packet) } catch (_: Throwable) {}
        try { avcodec.avcodec_free_context(context) } catch (_: Throwable) {}
        sws?.let { swscale.sws_freeContext(it) }
        sws = null
        rgb?.close()
        rgb = null
    }
}
