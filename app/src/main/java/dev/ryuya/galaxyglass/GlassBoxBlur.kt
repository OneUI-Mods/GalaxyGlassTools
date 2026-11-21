package dev.ryuya.galaxyglass

import java.util.Arrays
import kotlin.math.min

/** Separable clamped box blur. A vertical band retains its real neighbors for every pass. */
internal object GlassBoxBlur {
    data class Band(val sourceStart: Int, val sourceEnd: Int, val firstChanged: Int, val afterChanged: Int) {
        fun affectedStart(radius: Int): Int = (firstChanged - radius).coerceAtLeast(0)
        fun affectedEnd(radius: Int, height: Int): Int = (afterChanged + radius).coerceAtMost(height)
    }

    fun changedBand(old: IntArray, new: IntArray, width: Int, height: Int, radius: Int): Band? {
        val first = Arrays.mismatch(old, new)
        if (first < 0) return null
        val firstRow = first / width
        var lastRow = height - 1
        while (lastRow > firstRow) {
            val offset = lastRow * width
            if (!Arrays.equals(old, offset, offset + width, new, offset, offset + width)) break
            lastRow--
        }
        // The affected output needs a second radius of unchanged input on each side.
        return Band(
            (firstRow - 2 * radius).coerceAtLeast(0),
            (lastRow + 1 + 2 * radius).coerceAtMost(height),
            firstRow,
            lastRow + 1,
        )
    }

    fun apply(source: IntArray, scratch: IntArray, destination: IntArray, width: Int, height: Int, radius: Int) {
        blurLines(source, scratch, height, width, width, 1, radius)
        blurLines(scratch, destination, width, height, 1, width, radius)
    }

    private fun blurLines(
        source: IntArray,
        destination: IntArray,
        lineCount: Int,
        lineLength: Int,
        lineStride: Int,
        pixelStride: Int,
        radius: Int,
    ) {
        val windowSize = 2 * radius + 1
        val initialEntering = min(radius + 1, lineLength - 1) * pixelStride
        val lastOffset = (lineLength - 1) * pixelStride
        for (line in 0 until lineCount) {
            val first = line * lineStride
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val pixel = source[first + offset.coerceIn(0, lineLength - 1) * pixelStride]
                red += (pixel ushr 16) and 255
                green += (pixel ushr 8) and 255
                blue += pixel and 255
            }
            val last = first + lastOffset
            var leavingIndex = first
            var enteringIndex = first + initialEntering
            var destinationIndex = first
            for (offset in 0 until lineLength) {
                destination[destinationIndex] = 0xff000000.toInt() or
                    ((red / windowSize) shl 16) or ((green / windowSize) shl 8) or (blue / windowSize)
                val leaving = source[leavingIndex]
                val entering = source[enteringIndex]
                red += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
                green += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
                blue += (entering and 255) - (leaving and 255)
                // Keep the same clamped endpoints while advancing the addresses in place.
                if (offset >= radius) leavingIndex += pixelStride
                if (enteringIndex < last) enteringIndex += pixelStride
                destinationIndex += pixelStride
            }
        }
    }
}
