package dev.ryuya.galaxyglass

import java.util.Arrays
import kotlin.math.min

/** Separable clamped box blur. A cropped region retains its real neighbors for every pass. */
internal object GlassBoxBlur {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top

        fun expanded(radius: Int, width: Int, height: Int) = Bounds(
            (left - radius).coerceAtLeast(0),
            (top - radius).coerceAtLeast(0),
            (right + radius).coerceAtMost(width),
            (bottom + radius).coerceAtMost(height),
        )
    }

    fun changedBounds(old: IntArray, new: IntArray, width: Int, height: Int): Bounds? {
        val first = Arrays.mismatch(old, new)
        if (first < 0) return null
        if (first == 0 && old.last() != new.last()) return Bounds(0, 0, width, height)
        val firstRow = first / width
        var lastRow = height - 1
        while (lastRow > firstRow) {
            val offset = lastRow * width
            if (!Arrays.equals(old, offset, offset + width, new, offset, offset + width)) break
            lastRow--
        }
        var left = first % width
        var right = left + 1
        for (row in firstRow..lastRow) {
            val offset = row * width
            if (row > firstRow && left > 0) {
                val firstDiff = Arrays.mismatch(old, offset, offset + left, new, offset, offset + left)
                if (firstDiff >= 0) left = firstDiff
            }
            if (right < width) {
                val last = offset + width - 1
                if (old[last] != new[last]) {
                    right = width
                } else if (!Arrays.equals(old, offset + right, last, new, offset + right, last)) {
                    var x = width - 2
                    while (old[offset + x] == new[offset + x]) x--
                    right = x + 1
                }
            }
            if (left == 0 && right == width) break
        }
        return Bounds(left, firstRow, right, lastRow + 1)
    }

    fun copyRegion(source: IntArray, destination: IntArray, stride: Int, bounds: Bounds) {
        if (bounds.width == stride) {
            source.copyInto(destination, 0, bounds.top * stride, bounds.bottom * stride)
            return
        }
        for (row in 0 until bounds.height) {
            val start = (bounds.top + row) * stride + bounds.left
            source.copyInto(destination, row * bounds.width, start, start + bounds.width)
        }
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
