package dev.killua.iptv.domain.progress

import kotlin.math.abs

object WatchProgressPolicy {
    const val COMPLETION_PERCENT = 0.93
    const val REMAINING_THRESHOLD_MS = 3 * 60 * 1_000L
    const val END_TOLERANCE_MS = 2_000L
    const val WRITE_THRESHOLD_MS = 1_000L

    fun fraction(positionMs: Long, durationMs: Long): Double {
        if (durationMs <= 0L) return 0.0
        return (positionMs.coerceIn(0L, durationMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    /**
     * Whether playback has actually reached the end.
     *
     * Deliberately **not** [isCompleted]. That one answers "is this still worth resuming", and says
     * yes three minutes before the credits so that resuming does not drop the viewer into them.
     * Handing the next episode over at that moment would cut the last three minutes off every one.
     *
     * The tolerance is there because a provider's container routinely ends a second or two short of
     * the duration it advertises, and a position that never quite reaches the length would mean an
     * episode that never quite ends.
     */
    fun hasReachedEnd(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0L && positionMs > 0L && positionMs >= durationMs - END_TOLERANCE_MS

    /**
     * Whether a position has moved far enough from the last one written down to be worth writing.
     *
     * A client that checkpoints on a timer asks this every tick, and without it the answer is always
     * yes: a paused film rewrites the whole user-data file every ten seconds for as long as it stays
     * paused, and so does one stalled on a slow stream. Nothing about those rewrites is wrong — they
     * store the position that is already stored — they are simply a file rewritten hundreds of times
     * an evening to record that nothing happened.
     *
     * The distance is compared in **both directions**, because seeking backwards moves the position
     * as truly as playing forwards does, and a viewer who jumps back two minutes and closes the
     * window has changed where they are.
     *
     * The duration is part of the question and not decoration. It is what a progress bar divides by,
     * and a stream's length is sometimes not known for the first seconds of playback: a rule that
     * looked only at the position could freeze a duration of zero in place for as long as the
     * position did not move, which reads as a bar stuck empty rather than as a file not written.
     *
     * Nothing written yet always deserves a write; that is the first checkpoint of a title.
     */
    fun isWorthWriting(
        writtenPositionMs: Long?,
        writtenDurationMs: Long?,
        positionMs: Long,
        durationMs: Long,
    ): Boolean =
        writtenPositionMs == null ||
            writtenDurationMs != durationMs ||
            abs(positionMs - writtenPositionMs) >= WRITE_THRESHOLD_MS

    fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        val clampedPosition = positionMs.coerceIn(0L, durationMs)
        val remaining = durationMs - clampedPosition
        return fraction(clampedPosition, durationMs) >= COMPLETION_PERCENT ||
            (durationMs >= 10 * 60 * 1_000L && remaining <= REMAINING_THRESHOLD_MS)
    }
}
