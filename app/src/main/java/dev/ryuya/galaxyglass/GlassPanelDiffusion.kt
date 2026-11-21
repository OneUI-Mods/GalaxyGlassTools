package dev.ryuya.galaxyglass

import android.graphics.Bitmap
import java.util.Arrays
import kotlin.math.min

/** Produces the two shared textures on the capture worker. */
internal class GlassPanelDiffusion(private val skipUnchanged: Boolean) {
    data class Backdrops(
        val diffuse: Bitmap,
        val bevel: Bitmap,
        private val diffuseReused: Boolean = false,
        private val bevelReused: Boolean = false,
    ) {
        fun recycleUnpublished() {
            if (!diffuseReused) diffuse.recycle()
            if (!bevelReused) bevel.recycle()
        }
    }

    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    private var previous = IntArray(0)
    private var width = 0
    private var height = 0
    private var hasPrevious = false
    private var bevelRadius = 1
    private var diffuseRadius = 1
    private var previousBackdrops: Backdrops? = null
    var inputChanged: Boolean = false
        private set

    fun forgetPrevious() {
        hasPrevious = false
        previousBackdrops = null
    }

    /** Returns null if the rendered textures match the ones already published. */
    fun apply(bitmap: Bitmap): Backdrops? {
        inputChanged = false
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
        inputChanged = true
        if (skipUnchanged) {
            pixels = previous
            previous = input
            hasPrevious = true
        }

        val last = previousBackdrops
        // Include the unchanged input neighbors when estimating whether a patch is cheaper.
        val workBounds = if (changed != null && changed.width * changed.height <= pixels.size / 2) {
            changed.expanded(2 * totalRadius, width, height)
        } else null
        val next = if (changed != null && workBounds != null && last != null &&
            workBounds.width * workBounds.height <= pixels.size / 2
        ) {
            patch(input, changed, last, totalRadius)
        } else {
            GlassBoxBlur.apply(input, scratch, pixels, width, height, bevelRadius)
            val bevel = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            repeat(2) { GlassBoxBlur.apply(pixels, scratch, pixels, width, height, diffuseRadius) }
            Backdrops(Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888), bevel)
        }
        if (skipUnchanged && next != null) previousBackdrops = next
        return next
    }

    private fun patch(
        input: IntArray,
        changed: GlassBoxBlur.Bounds,
        last: Backdrops,
        totalRadius: Int,
    ): Backdrops? {
        // Each pass writes only the affected output plus the neighbors needed by later passes.
        val diffuseBounds = changed.expanded(totalRadius, width, height)
        val firstDiffuseInput = changed.expanded(totalRadius + 2 * diffuseRadius, width, height)
        val secondDiffuseInput = changed.expanded(totalRadius + diffuseRadius, width, height)
        GlassBoxBlur.applyRegion(input, scratch, pixels, width, height, bevelRadius, firstDiffuseInput)
        val bevelOutput = changed.expanded(bevelRadius, width, height)
        val bevel = patchTexture(last.bevel, firstDiffuseInput, bevelOutput)
        val bevelReused = bevel === last.bevel
        try {
            GlassBoxBlur.applyRegion(pixels, scratch, pixels, firstDiffuseInput.width, firstDiffuseInput.height,
                diffuseRadius, secondDiffuseInput.relativeTo(firstDiffuseInput))
            GlassBoxBlur.applyRegion(pixels, scratch, pixels, secondDiffuseInput.width, secondDiffuseInput.height,
                diffuseRadius, diffuseBounds.relativeTo(secondDiffuseInput))
            val diffuse = patchTexture(last.diffuse, diffuseBounds, diffuseBounds)
            val diffuseReused = diffuse === last.diffuse
            return if (diffuseReused && bevelReused) null
                else Backdrops(diffuse, bevel, diffuseReused, bevelReused)
        } catch (error: Exception) {
            if (!bevelReused) bevel.recycle()
            throw error
        }
    }

    private fun patchTexture(old: Bitmap, input: GlassBoxBlur.Bounds, output: GlassBoxBlur.Bounds): Bitmap {
        val offset = (output.top - input.top) * input.width + output.left - input.left
        old.getPixels(scratch, 0, output.width, output.left, output.top, output.width, output.height)
        val unchanged = (0 until output.height).all { row ->
            val previousStart = row * output.width
            val nextStart = offset + row * input.width
            Arrays.equals(scratch, previousStart, previousStart + output.width,
                pixels, nextStart, nextStart + output.width)
        }
        if (unchanged) return old
        // Published bitmaps can still be referenced by a recorded display list.
        val copy = checkNotNull(old.copy(Bitmap.Config.ARGB_8888, true)) { "Backdrop copy failed" }
        copy.setPixels(pixels, offset, input.width, output.left, output.top, output.width, output.height)
        return copy
    }
}
