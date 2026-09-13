package org.jellyfin.mobile.player.ui

import android.graphics.Bitmap
import kotlin.math.max

/** Small, allocation-bounded box blur used for the live hard-subtitle mask preview. */
object BlurBitmapProcessor {
    private const val MAX_WIDTH = 360
    private const val RADIUS = 8

    fun blur(source: Bitmap): Bitmap {
        val targetWidth = minOf(source.width, MAX_WIDTH)
        val targetHeight = max(1, source.height * targetWidth / source.width)
        val reduced = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        if (reduced !== source) source.recycle()
        val pixels = IntArray(targetWidth * targetHeight)
        val scratch = IntArray(pixels.size)
        reduced.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        repeat(3) {
            blurHorizontally(pixels, scratch, targetWidth, targetHeight)
            blurVertically(scratch, pixels, targetWidth, targetHeight)
        }
        reduced.setPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        return reduced
    }

    private fun blurHorizontally(input: IntArray, output: IntArray, width: Int, height: Int) {
        val span = RADIUS * 2 + 1
        for (y in 0 until height) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            val row = y * width
            for (offset in -RADIUS..RADIUS) {
                val color = input[row + offset.coerceIn(0, width - 1)]
                a += color ushr 24
                r += color ushr 16 and 0xff
                g += color ushr 8 and 0xff
                b += color and 0xff
            }
            for (x in 0 until width) {
                output[row + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                val remove = input[row + (x - RADIUS).coerceIn(0, width - 1)]
                val add = input[row + (x + RADIUS + 1).coerceIn(0, width - 1)]
                a += (add ushr 24) - (remove ushr 24)
                r += (add ushr 16 and 0xff) - (remove ushr 16 and 0xff)
                g += (add ushr 8 and 0xff) - (remove ushr 8 and 0xff)
                b += (add and 0xff) - (remove and 0xff)
            }
        }
    }

    private fun blurVertically(input: IntArray, output: IntArray, width: Int, height: Int) {
        val span = RADIUS * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (offset in -RADIUS..RADIUS) {
                val color = input[offset.coerceIn(0, height - 1) * width + x]
                a += color ushr 24
                r += color ushr 16 and 0xff
                g += color ushr 8 and 0xff
                b += color and 0xff
            }
            for (y in 0 until height) {
                output[y * width + x] = (a / span shl 24) or (r / span shl 16) or (g / span shl 8) or (b / span)
                val remove = input[(y - RADIUS).coerceIn(0, height - 1) * width + x]
                val add = input[(y + RADIUS + 1).coerceIn(0, height - 1) * width + x]
                a += (add ushr 24) - (remove ushr 24)
                r += (add ushr 16 and 0xff) - (remove ushr 16 and 0xff)
                g += (add ushr 8 and 0xff) - (remove ushr 8 and 0xff)
                b += (add and 0xff) - (remove and 0xff)
            }
        }
    }
}
