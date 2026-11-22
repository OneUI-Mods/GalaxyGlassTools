package dev.ryuya.galaxyglass

internal data class PipPlaybackPosition(
    val positionMs: Long,
    val updatedAtMs: Long,
    val speed: Float,
    val moving: Boolean,
) {
    fun positionAt(durationMs: Long, nowMs: Long): Long? {
        if (durationMs <= 0 || positionMs < 0) return null
        if (!moving || !speed.isFinite() || updatedAtMs <= 0 || updatedAtMs > nowMs) {
            return positionMs.coerceAtMost(durationMs)
        }
        val elapsed = nowMs - updatedAtMs
        return (positionMs + elapsed * speed.toDouble())
            .coerceIn(0.0, durationMs.toDouble()).toLong()
    }
}
