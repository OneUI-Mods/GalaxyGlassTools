package dev.ryuya.galaxyglass

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
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
    fun partialUpdatesMatchFullFramesIncludingTopAndBottomEdges() {
        val width = 36
        val height = 220
        val bevelRadius = 2
        val diffuseRadius = 4
        val totalRadius = bevelRadius + 2 * diffuseRadius
        val random = Random(506)
        val input = IntArray(width * height) { random.nextInt() }
        for (rows in listOf(0..2, 96..99, 218..219, 108..108)) {
            val changed = input.copyOf()
            for (y in rows) changed[y * width + 17] = changed[y * width + 17].inv()
            val band = checkNotNull(GlassBoxBlur.changedBand(input, changed, width, height, totalRadius))
            val full = changed.copyOf()
            val old = input.copyOf()
            val patch = changed.copyOfRange(band.sourceStart * width, band.sourceEnd * width)
            var radius = 0
            for (pass in listOf(bevelRadius, diffuseRadius, diffuseRadius)) {
                radius += pass
                GlassBoxBlur.apply(full, IntArray(full.size), full, width, height, pass)
                GlassBoxBlur.apply(old, IntArray(old.size), old, width, height, pass)
                GlassBoxBlur.apply(patch, IntArray(patch.size), patch, width, band.sourceEnd - band.sourceStart, pass)
                val start = band.affectedStart(radius)
                val end = band.affectedEnd(radius, height)
                val merged = old.copyOf()
                patch.copyInto(merged, start * width, (start - band.sourceStart) * width, (end - band.sourceStart) * width)
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
