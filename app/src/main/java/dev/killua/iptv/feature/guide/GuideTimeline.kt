package dev.killua.iptv.feature.guide

import dev.killua.iptv.domain.model.EpgEntry

/**
 * Where a programme sits on the guide's shared time axis, as fractions of the visible window.
 *
 * Fractions rather than pixels: every row draws against the same window, and the row does not know
 * how wide it will be laid out. A programme that runs past either edge is clipped to it, so a film
 * that started an hour ago still shows the part of it that is left.
 */
internal data class GuideSlot(
    val entry: EpgEntry,
    val startFraction: Float,
    val endFraction: Float,
) {
    val widthFraction: Float get() = endFraction - startFraction
}

/**
 * The window a fresh guide opens on: the previous half hour, so the programme currently running is
 * visible from its start rather than cut off at the left edge.
 */
internal fun guideWindowStart(nowEpochSeconds: Long): Long =
    Math.floorDiv(nowEpochSeconds, HALF_HOUR_SECONDS) * HALF_HOUR_SECONDS

/** How much time the guide shows at once. Four hours is about an evening's worth of planning. */
internal const val GUIDE_WINDOW_SECONDS: Long = 4 * 60 * 60

/**
 * Places [entries] on the window, dropping what falls outside it entirely.
 *
 * Providers are not careful with these timestamps: entries arrive out of order, occasionally with an
 * end at or before the start, and occasionally overlapping. Anything without positive length inside
 * the window is dropped rather than drawn as a sliver that cannot be read or tapped.
 */
internal fun guideSlots(
    entries: List<EpgEntry>,
    windowStartEpochSeconds: Long,
    windowEndEpochSeconds: Long,
): List<GuideSlot> {
    val span = windowEndEpochSeconds - windowStartEpochSeconds
    if (span <= 0L) return emptyList()
    return entries
        .asSequence()
        .filter { it.endEpochSeconds > windowStartEpochSeconds }
        .filter { it.startEpochSeconds < windowEndEpochSeconds }
        .sortedBy { it.startEpochSeconds }
        .mapNotNull { entry ->
            val start = entry.startEpochSeconds.coerceAtLeast(windowStartEpochSeconds)
            val end = entry.endEpochSeconds.coerceAtMost(windowEndEpochSeconds)
            if (end <= start) return@mapNotNull null
            GuideSlot(
                entry = entry,
                startFraction = (start - windowStartEpochSeconds).toFloat() / span.toFloat(),
                endFraction = (end - windowStartEpochSeconds).toFloat() / span.toFloat(),
            )
        }
        .toList()
}

/**
 * The hour boundaries inside the window, for the ruler above the rows.
 *
 * Whole hours only: a ruler that also marked half hours would be unreadable at the width a phone
 * can give four hours.
 */
internal fun guideHourMarks(
    windowStartEpochSeconds: Long,
    windowEndEpochSeconds: Long,
): List<Long> {
    if (windowEndEpochSeconds <= windowStartEpochSeconds) return emptyList()
    val firstHour = Math.floorDiv(windowStartEpochSeconds + HOUR_SECONDS - 1, HOUR_SECONDS) *
        HOUR_SECONDS
    return generateSequence(firstHour) { it + HOUR_SECONDS }
        .takeWhile { it < windowEndEpochSeconds }
        .toList()
}

/** Where a moment sits in the window, or null when it is outside it. */
internal fun guideFractionOf(
    epochSeconds: Long,
    windowStartEpochSeconds: Long,
    windowEndEpochSeconds: Long,
): Float? {
    val span = windowEndEpochSeconds - windowStartEpochSeconds
    if (span <= 0L) return null
    if (epochSeconds < windowStartEpochSeconds || epochSeconds > windowEndEpochSeconds) return null
    return (epochSeconds - windowStartEpochSeconds).toFloat() / span.toFloat()
}

private const val HOUR_SECONDS = 60L * 60L
private const val HALF_HOUR_SECONDS = 30L * 60L
