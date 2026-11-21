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

        fun relativeTo(input: Bounds) =
            Bounds(left - input.left, top - input.top, right - input.left, bottom - input.top)
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

    fun apply(source: IntArray, scratch: IntArray, destination: IntArray, width: Int, height: Int, radius: Int) {
        // Transpose the first result so both passes read contiguous scanlines. The second pass
        // transposes back, preserving the rounding between the horizontal and vertical passes.
        blurLines(source, scratch, width, 0, height, 0, width, radius)
        blurLines(scratch, destination, height, 0, width, 0, height, radius)
    }

    /** Writes only [bounds] to a tightly packed output, with the same samples as a full pass. */
    fun applyRegion(source: IntArray, scratch: IntArray, destination: IntArray,
        width: Int, height: Int, radius: Int, bounds: Bounds,
    ) {
        val firstRow = (bounds.top - radius).coerceAtLeast(0)
        val lastRow = (bounds.bottom + radius).coerceAtMost(height)
        val rows = lastRow - firstRow
        blurLines(source, scratch, width, firstRow, rows, bounds.left, bounds.right, radius)
        blurLines(scratch, destination, rows, 0, bounds.width, bounds.top - firstRow, bounds.bottom - firstRow, radius)
    }

    private fun blurLines(
        source: IntArray,
        destination: IntArray,
        lineLength: Int,
        firstLine: Int,
        lineCount: Int,
        start: Int,
        end: Int,
        radius: Int,
    ) {
        val windowSize = 2 * radius + 1
        // Rounding the reciprocal up preserves floor(sum / size) for every possible 8-bit
        // channel sum when 255 * size * size < 2^20. Larger windows use integer division.
        val reciprocal = if (windowSize in 1..63) ((1 shl 20) + windowSize - 1) / windowSize else 0
        val lastOffset = lineLength - 1
        val initialLeaving = (start - radius).coerceAtLeast(0)
        val initialEntering = min(start + radius + 1, lastOffset)
        for (line in 0 until lineCount) {
            val first = (line + firstLine) * lineLength
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val pixel = source[first + (start + offset).coerceIn(0, lastOffset)]
                red += (pixel ushr 16) and 255
                green += (pixel ushr 8) and 255
                blue += pixel and 255
            }
            val last = first + lastOffset
            var leavingIndex = first + initialLeaving
            var enteringIndex = first + initialEntering
            var destinationIndex = line
            for (offset in start until end) {
                val rgb = if (reciprocal != 0) {
                    (((red * reciprocal) ushr 20) shl 16) or
                        (((green * reciprocal) ushr 20) shl 8) or ((blue * reciprocal) ushr 20)
                } else {
                    ((red / windowSize) shl 16) or ((green / windowSize) shl 8) or (blue / windowSize)
                }
                destination[destinationIndex] = 0xff000000.toInt() or rgb
                val leaving = source[leavingIndex]
                val entering = source[enteringIndex]
                red += ((entering ushr 16) and 255) - ((leaving ushr 16) and 255)
                green += ((entering ushr 8) and 255) - ((leaving ushr 8) and 255)
                blue += (entering and 255) - (leaving and 255)
                // Keep the same clamped endpoints while advancing the addresses in place.
                if (offset >= radius) leavingIndex++
                if (enteringIndex < last) enteringIndex++
                destinationIndex += lineCount
            }
        }
    }
}
