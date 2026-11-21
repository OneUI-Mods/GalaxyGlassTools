package dev.ryuya.galaxyglass

import android.graphics.Bitmap
import kotlin.math.min

/** Produces the two shared textures on the capture worker. */
internal class GlassPanelDiffusion(private val skipUnchanged: Boolean) {
    data class Backdrops(val diffuse: Bitmap, val bevel: Bitmap)

    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    private var previous = IntArray(0)
    private var width = 0
    private var height = 0
    private var hasPrevious = false
    private var bevelRadius = 1
    private var diffuseRadius = 1
    private var previousBackdrops: Backdrops? = null

    fun forgetPrevious() {
        hasPrevious = false
        previousBackdrops = null
    }

    /** Returns null if the input has exactly the same pixels as the previous frame. */
    fun apply(bitmap: Bitmap): Backdrops? {
        val software = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Backdrop software copy failed") else bitmap
        try {
            if (width != software.width || height != software.height) {
                width = software.width
                height = software.height
                pixels = IntArray(width * height)
                scratch = IntArray(pixels.size)
                if (skipUnchanged) previous = IntArray(pixels.size)
                val shortSide = min(width, height)
                bevelRadius = (shortSide / 44).coerceAtLeast(1)
                diffuseRadius = (shortSide / 18).coerceAtLeast(1)
                forgetPrevious()
            }
            software.getPixels(pixels, 0, width, 0, 0, width, height)
        } finally {
            if (software !== bitmap) software.recycle()
        }
        val input = pixels
        val totalRadius = bevelRadius + 2 * diffuseRadius
        val band = if (skipUnchanged && hasPrevious) {
            GlassBoxBlur.changedBand(previous, input, width, height, totalRadius) ?: return null
        } else null
        if (skipUnchanged) {
            pixels = previous
            previous = input
            hasPrevious = true
        }

        val last = previousBackdrops
        val next = if (band != null && last != null && band.sourceEnd - band.sourceStart <= height / 2) {
            patch(input, band, last, totalRadius)
        } else {
            GlassBoxBlur.apply(input, scratch, pixels, width, height, bevelRadius)
            val bevel = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            repeat(2) { GlassBoxBlur.apply(pixels, scratch, pixels, width, height, diffuseRadius) }
            Backdrops(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), bevel)
        }
        if (skipUnchanged) previousBackdrops = next
        return next
    }

    private fun patch(input: IntArray, band: GlassBoxBlur.Band, last: Backdrops, totalRadius: Int): Backdrops {
        val lines = band.sourceEnd - band.sourceStart
        input.copyInto(pixels, 0, band.sourceStart * width, band.sourceEnd * width)
        GlassBoxBlur.apply(pixels, scratch, pixels, width, lines, bevelRadius)
        val bevel = patchTexture(last.bevel, band, bevelRadius)
        try {
            repeat(2) { GlassBoxBlur.apply(pixels, scratch, pixels, width, lines, diffuseRadius) }
            return Backdrops(patchTexture(last.diffuse, band, totalRadius), bevel)
        } catch (error: Exception) {
            bevel.recycle()
            throw error
        }
    }

    private fun patchTexture(old: Bitmap, band: GlassBoxBlur.Band, radius: Int): Bitmap {
        // Published bitmaps can still be referenced by a recorded display list.
        val copy = checkNotNull(old.copy(Bitmap.Config.ARGB_8888, true)) { "Backdrop copy failed" }
        val start = band.affectedStart(radius)
        val end = band.affectedEnd(radius, height)
        copy.setPixels(pixels, (start - band.sourceStart) * width, width, 0, start, width, end - start)
        return copy
    }
}
