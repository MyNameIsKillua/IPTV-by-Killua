package dev.killua.iptv.domain.epg

import dev.killua.iptv.domain.model.EpgEntry

/**
 * Picks what is on now and what follows out of a channel's guide.
 *
 * Kept out of the UI so the boundary cases are testable: providers send overlapping entries, leave
 * gaps between programmes, and return listings that ended hours ago.
 */
object EpgSelection {
    /** The programme covering [atEpochSeconds], or null in a gap or outside the listing. */
    fun nowPlaying(entries: List<EpgEntry>, atEpochSeconds: Long): EpgEntry? = entries
        .filter { atEpochSeconds >= it.startEpochSeconds && atEpochSeconds < it.endEpochSeconds }
        // Providers do send overlapping entries; the one that started last is the current one.
        .maxByOrNull { it.startEpochSeconds }

    /**
     * The next programme to start after [atEpochSeconds].
     *
     * Defined by start time rather than "the entry after the current one", so it is still correct
     * in a gap between programmes and when the listing overlaps.
     */
    fun upNext(entries: List<EpgEntry>, atEpochSeconds: Long): EpgEntry? = entries
        .filter { it.startEpochSeconds > atEpochSeconds }
        .minByOrNull { it.startEpochSeconds }

    /**
     * How far through the current programme [atEpochSeconds] is, from 0 to 1.
     *
     * Null when nothing is on, so a caller shows no bar rather than an empty one.
     */
    fun progress(entries: List<EpgEntry>, atEpochSeconds: Long): Float? {
        val current = nowPlaying(entries, atEpochSeconds) ?: return null
        val length = current.endEpochSeconds - current.startEpochSeconds
        if (length <= 0L) return null
        val elapsed = atEpochSeconds - current.startEpochSeconds
        return (elapsed.toFloat() / length.toFloat()).coerceIn(0f, 1f)
    }
}
