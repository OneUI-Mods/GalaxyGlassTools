package dev.ryuya.galaxyglass

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassBoxBlurTest {
    @Test
    fun matchesClampedNativeSizedAndDegenerateFramesAfterEveryPass() {
        val random = Random(321)
        for ((width, height, radius) in listOf(
            Triple(1, 1, 1), Triple(1, 23, 4), Triple(32, 1, 3),
            Triple(3, 2, 5), Triple(41, 27, 3), Triple(240, 520, 13),
        )) {
            val image = IntArray(width * height) { random.nextInt() }
            val actual = image.copyOf()
            var expected = image.copyOf()
            val scratch = IntArray(image.size)
            repeat(3) {
                expected = reference(reference(expected, width, height, radius, true), width, height, radius, false)
                GlassBoxBlur.apply(actual, scratch, actual, width, height, radius)
                assertArrayEquals(expected, actual)
            }
        }
    }

    @Test
    fun partialUpdatesMatchFullFramesAtAllEdgesAndAcrossMultipleRows() {
        val width = 84
        val height = 220
        val bevelRadius = 2
        val diffuseRadius = 4
        val totalRadius = bevelRadius + 2 * diffuseRadius
        val random = Random(506)
        val input = IntArray(width * height) { random.nextInt() }
        assertNull(GlassBoxBlur.changedBounds(input, input.copyOf(), width, height))
        val regions = listOf(
            GlassBoxBlur.Bounds(0, 0, 4, 3),
            GlassBoxBlur.Bounds(80, 0, 84, 3),
            GlassBoxBlur.Bounds(0, 218, 4, 220),
            GlassBoxBlur.Bounds(80, 218, 84, 220),
            GlassBoxBlur.Bounds(40, 96, 42, 99),
            GlassBoxBlur.Bounds(0, 98, 84, 101),
            GlassBoxBlur.Bounds(31, 0, 32, 220),
        )
        for (region in regions) {
            val changed = input.copyOf()
            for (y in region.top until region.bottom) {
                // Reverse the x ordering across rows to exercise both bounding directions.
                val x = if (y % 2 == 0) region.left else region.right - 1
                changed[y * width + x] = changed[y * width + x].inv()
            }
            val dirty = checkNotNull(GlassBoxBlur.changedBounds(input, changed, width, height))
            assertEquals(region, dirty)
            val crop = dirty.expanded(2 * totalRadius, width, height)
            val full = changed.copyOf()
            val old = input.copyOf()
            val patch = IntArray(crop.width * crop.height)
            GlassBoxBlur.copyRegion(changed, patch, width, crop)
            var radius = 0
            for (pass in listOf(bevelRadius, diffuseRadius, diffuseRadius)) {
                radius += pass
                GlassBoxBlur.apply(full, IntArray(full.size), full, width, height, pass)
                GlassBoxBlur.apply(old, IntArray(old.size), old, width, height, pass)
                GlassBoxBlur.apply(patch, IntArray(patch.size), patch, crop.width, crop.height, pass)
                val output = dirty.expanded(radius, width, height)
                val merged = old.copyOf()
                for (row in output.top until output.bottom) {
                    val offset = (row - crop.top) * crop.width + output.left - crop.left
                    patch.copyInto(merged, row * width + output.left, offset, offset + output.width)
                }
                assertArrayEquals(full, merged)
            }
        }
    }

    private fun reference(pixels: IntArray, width: Int, height: Int, radius: Int, horizontal: Boolean) =
        IntArray(pixels.size) { index ->
            val x = index % width
            val y = index / width
            var r = 0
            var g = 0
            var b = 0
            for (step in -radius..radius) {
                val point = if (horizontal) y * width + (x + step).coerceIn(0, width - 1)
                    else (y + step).coerceIn(0, height - 1) * width + x
                val pixel = pixels[point]
                r += (pixel ushr 16) and 255
                g += (pixel ushr 8) and 255
                b += pixel and 255
            }
            val divisor = radius * 2 + 1
            -0x1000000 or ((r / divisor) shl 16) or ((g / divisor) shl 8) or (b / divisor)
        }
}
