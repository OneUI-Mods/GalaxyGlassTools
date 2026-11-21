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
        val changed = if (skipUnchanged && hasPrevious) {
            GlassBoxBlur.changedBounds(previous, input, width, height) ?: return null
        } else null
        if (skipUnchanged) {
            pixels = previous
            previous = input
            hasPrevious = true
        }

        val last = previousBackdrops
        // An affected output pixel needs another full filter radius of unchanged input.
        val inputBounds = if (changed != null && changed.width * changed.height <= pixels.size / 2) {
            changed.expanded(2 * totalRadius, width, height)
        } else null
        val next = if (changed != null && inputBounds != null && last != null &&
            inputBounds.width * inputBounds.height <= pixels.size / 2
        ) {
            patch(input, inputBounds, changed, last, totalRadius)
        } else {
            GlassBoxBlur.apply(input, scratch, pixels, width, height, bevelRadius)
            val bevel = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            repeat(2) { GlassBoxBlur.apply(pixels, scratch, pixels, width, height, diffuseRadius) }
            Backdrops(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), bevel)
        }
        if (skipUnchanged) previousBackdrops = next
        return next
    }

    private fun patch(
        input: IntArray,
        inputBounds: GlassBoxBlur.Bounds,
        changed: GlassBoxBlur.Bounds,
        last: Backdrops,
        totalRadius: Int,
    ): Backdrops {
        GlassBoxBlur.copyRegion(input, pixels, width, inputBounds)
        GlassBoxBlur.apply(pixels, scratch, pixels, inputBounds.width, inputBounds.height, bevelRadius)
        val bevel = patchTexture(last.bevel, inputBounds, changed.expanded(bevelRadius, width, height))
        try {
            repeat(2) {
                GlassBoxBlur.apply(pixels, scratch, pixels, inputBounds.width, inputBounds.height, diffuseRadius)
            }
            return Backdrops(patchTexture(last.diffuse, inputBounds, changed.expanded(totalRadius, width, height)), bevel)
        } catch (error: Exception) {
            bevel.recycle()
            throw error
        }
    }

    private fun patchTexture(old: Bitmap, input: GlassBoxBlur.Bounds, output: GlassBoxBlur.Bounds): Bitmap {
        // Published bitmaps can still be referenced by a recorded display list.
        val copy = checkNotNull(old.copy(Bitmap.Config.ARGB_8888, true)) { "Backdrop copy failed" }
        val offset = (output.top - input.top) * input.width + output.left - input.left
        copy.setPixels(pixels, offset, input.width, output.left, output.top, output.width, output.height)
        return copy
    }
}
