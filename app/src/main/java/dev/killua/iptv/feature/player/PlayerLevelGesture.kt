package dev.killua.iptv.feature.player

import kotlin.math.roundToInt

/**
 * The arithmetic behind the vertical brightness/volume drag, kept out of the view.
 *
 * `GestureAwarePlayerView` is an Android `View` and cannot be exercised on the JVM, so everything
 * about *where* a drag lands and *what level* it produces lives here where it is testable. The view
 * keeps only the parts that genuinely need a touch stream.
 */
internal enum class PlayerLevelTarget {
    Brightness,
    Volume,
}

/** What the slider is called, for the accessibility description. */
internal val PlayerLevelTarget.cueLabel: String
    get() = when (this) {
        PlayerLevelTarget.Brightness -> "Brightness"
        PlayerLevelTarget.Volume -> "Volume"
    }

/** What the on-screen slider is currently showing. Null while no drag is in progress. */
internal data class PlayerLevelIndicator(
    val target: PlayerLevelTarget,
    val level: Float,
)

/**
 * The touchable band around each slider, in pixels.
 *
 * Supplied by Compose from the same measurements it draws with. If these two ever drift apart the
 * slider would sit somewhere other than where dragging works, which is exactly the complaint that
 * replaced the old whole-half gesture.
 */
internal data class PlayerLevelBands(
    /** How far in from the left and right edges a touch still counts. */
    val widthPx: Float,
    /** Total height of the band, centred vertically like the slider itself. */
    val heightPx: Float,
)

/**
 * Which slider a touch belongs to, or null when it is not on one.
 *
 * The gesture used to own each whole half of the picture, which made it far too easy to trigger by
 * accident and gave the drawn slider no honest relationship to where it worked. Now only a band
 * around the visible slider responds — with enough margin that it does not have to be hit exactly.
 */
internal fun resolvePlayerLevelTarget(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    bands: PlayerLevelBands,
): PlayerLevelTarget? {
    if (width <= 0f || height <= 0f || bands.widthPx <= 0f || bands.heightPx <= 0f) return null
    if (kotlin.math.abs(y - height / 2f) > bands.heightPx / 2f) return null
    // Two bands cannot overlap in the middle: a screen narrower than both is not a real device,
    // but the left one winning is at least deterministic.
    return when {
        x <= bands.widthPx -> PlayerLevelTarget.Brightness
        x >= width - bands.widthPx -> PlayerLevelTarget.Volume
        else -> null
    }
}

/**
 * The level after dragging [dragPixels] upward from [startLevel] over a range [rangePixels] long.
 *
 * The range is the on-screen slider's own track, not the height of the screen. Dragging the length
 * of the visible track covers the whole scale, so the gesture and the thing it draws describe the
 * same movement — a full-screen sweep would leave the slider looking like it lied about the
 * distance. [startLevel] is the value read when the drag was recognized, so the level moves on from
 * where the device already was rather than jumping to wherever the finger happens to be.
 */
internal fun playerLevelAfterDrag(
    startLevel: Float,
    dragPixels: Float,
    rangePixels: Float,
): Float {
    val start = startLevel.coerceIn(0f, 1f)
    if (rangePixels <= 0f) return start
    return (start + dragPixels / rangePixels).coerceIn(0f, 1f)
}

/** Whole percent for the on-screen cue; the level itself is never rounded. */
internal fun playerLevelPercent(level: Float): Int = (level.coerceIn(0f, 1f) * 100f).roundToInt()

/**
 * How tall the slider's track should be, in dp, on a picture [availableHeightDp] tall.
 *
 * A fixed track filled most of a landscape phone, which made a secondary control the largest thing
 * on screen. Scaling it with the picture keeps it modest on every device, and because the track is
 * also the drag distance, the gesture shrinks with it. The clamps stop it from becoming unusably
 * twitchy in a small window or absurdly long on a tablet.
 */
internal fun playerLevelTrackHeightDp(availableHeightDp: Float): Float =
    (availableHeightDp * LEVEL_TRACK_FRACTION).coerceIn(LEVEL_TRACK_MIN_DP, LEVEL_TRACK_MAX_DP)

private const val LEVEL_TRACK_FRACTION = 0.32f
private const val LEVEL_TRACK_MIN_DP = 76f
private const val LEVEL_TRACK_MAX_DP = 132f

/**
 * Nearest stream index for a 0..1 level.
 *
 * Android exposes volume as a handful of steps, not a continuous value, so a long drag crosses far
 * fewer distinct levels than it looks like it does. Rounding rather than truncating is what makes
 * the top of the drag actually reach maximum volume.
 */
internal fun streamVolumeIndexFor(level: Float, maxIndex: Int): Int {
    if (maxIndex <= 0) return 0
    return (level.coerceIn(0f, 1f) * maxIndex).roundToInt().coerceIn(0, maxIndex)
}

/** The 0..1 level a stream index represents, so a drag can continue from the current volume. */
internal fun streamVolumeLevelOf(index: Int, maxIndex: Int): Float {
    if (maxIndex <= 0) return 0f
    return (index.toFloat() / maxIndex.toFloat()).coerceIn(0f, 1f)
}
