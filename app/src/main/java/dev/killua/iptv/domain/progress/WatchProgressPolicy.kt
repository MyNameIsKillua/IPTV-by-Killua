package dev.killua.iptv.domain.progress

object WatchProgressPolicy {
    const val COMPLETION_PERCENT = 0.93
    const val REMAINING_THRESHOLD_MS = 3 * 60 * 1_000L

    fun fraction(positionMs: Long, durationMs: Long): Double {
        if (durationMs <= 0L) return 0.0
        return (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val clampedPosition = positionMs.coerceIn(0L, durationMs)
        val remaining = durationMs - clampedPosition
        return fraction(clampedPosition, durationMs) >= COMPLETION_PERCENT ||
            (durationMs >= 10 * 60 * 1_000L && remaining <= REMAINING_THRESHOLD_MS)
    }
}
