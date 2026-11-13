package com.scylla.tool.phonemic.pc

import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import kotlin.math.max
import kotlin.math.min

/**
 * Turns decoded phone frames into one fixed-size landscape canvas: rotated upright, optionally
 * mirrored, scaled to fit and letterboxed on black. The canvas size comes from the first frame
 * of a connection and never changes after that, because a virtual webcam can't change size
 * mid-stream (flipping to a camera with another resolution just letterboxes differently).
 */
class FrameComposer(firstWidth: Int, firstHeight: Int) {
    val width = max(firstWidth, firstHeight) and 1.inv()
    val height = min(firstWidth, firstHeight) and 1.inv()

    // Two canvases, alternated: the preview keeps painting the previous one while the next is composed.
    private val canvases = Array(2) { BufferedImage(width, height, BufferedImage.TYPE_INT_RGB) }
    private var next = 0

    fun compose(source: BufferedImage, quarterTurns: Int, mirror: Boolean): BufferedImage {
        val canvas = canvases[next]
        next = 1 - next
        val turns = ((quarterTurns % 4) + 4) % 4
        val g = canvas.createGraphics()
        try {
            if (turns == 0 && !mirror && source.width == width && source.height == height) {
                g.drawImage(source, 0, 0, null)
                return canvas
            }
            g.color = Color.BLACK
            g.fillRect(0, 0, width, height)
            val rotatedWidth = if (turns % 2 == 0) source.width else source.height
            val rotatedHeight = if (turns % 2 == 0) source.height else source.width
            val scale = min(width.toDouble() / rotatedWidth, height.toDouble() / rotatedHeight)
            // Read bottom-up: centre the source on the origin, rotate, scale, mirror, move to canvas centre.
            val transform = AffineTransform()
            transform.translate(width / 2.0, height / 2.0)
            if (mirror) transform.scale(-1.0, 1.0)
            transform.scale(scale, scale)
            transform.quadrantRotate(turns)
            transform.translate(-source.width / 2.0, -source.height / 2.0)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(source, transform, null)
        } finally {
            g.dispose()
        }
        return canvas
    }

    fun toNv12(canvas: BufferedImage, out: ByteArray) = rgbToNv12(canvas, out)
}

/**
 * BT.601 limited-range RGB -> NV12 (Y plane, then interleaved U,V at half resolution).
 * [image] must be TYPE_INT_RGB with even dimensions; [out] holds width*height*3/2 bytes.
 */
fun rgbToNv12(image: BufferedImage, out: ByteArray) {
    val pixels = (image.raster.dataBuffer as DataBufferInt).data
    val w = image.width
    val h = image.height
    var uv = w * h
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) {
            val p = pixels[row + x]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[row + x] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
        }
        if (y and 1 == 0) {
            var x = 0
            while (x < w) {
                // Chroma from the top-left pixel of each 2x2 block - cheap and fine for video.
                val p = pixels[row + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[uv++] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                out[uv++] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                x += 2
            }
        }
    }
}
