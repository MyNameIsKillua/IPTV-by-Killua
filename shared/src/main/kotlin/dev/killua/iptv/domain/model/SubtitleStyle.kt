package dev.killua.iptv.domain.model

/**
 * How subtitles are drawn, remembered across sessions.
 *
 * Both halves default to [SubtitleTextSize.System] and [SubtitleBackground.System], which means
 * Android's own caption preferences under Accessibility. That default is deliberate: someone who has
 * set large yellow captions system-wide did so because they need them, and an app that quietly
 * overrides that has made an accessibility setting useless. This screen only takes over once the
 * viewer asks it to.
 */
data class SubtitleStyle(
    val textSize: SubtitleTextSize = SubtitleTextSize.System,
    val background: SubtitleBackground = SubtitleBackground.System,
) {
    val isDefault: Boolean
        get() = textSize == SubtitleTextSize.System && background == SubtitleBackground.System

    /**
     * Whether the subtitle track's own styling still applies.
     *
     * A chosen background has to win over the stream's, or the setting would look broken on exactly
     * the streams that carry styling. Media3 ties font sizes to the same switch: turning embedded
     * styles off also stops embedded sizes from being honoured, whatever [appliesEmbeddedFontSizes]
     * says, so a chosen background implies a fixed size too.
     */
    val appliesEmbeddedStyles: Boolean
        get() = background == SubtitleBackground.System

    val appliesEmbeddedFontSizes: Boolean
        get() = textSize == SubtitleTextSize.System
}

/**
 * How large subtitles are drawn, as a fraction of the player's height.
 *
 * Fractional rather than absolute: the player is full-screen landscape on a phone and a fixed point
 * size would be a different physical size on every device, and would not follow the picture at all.
 * [System] carries no fraction because it is not a size — it defers to the platform.
 */
enum class SubtitleTextSize(val fraction: Float?) {
    System(null),
    Small(0.04f),
    Normal(MEDIA3_DEFAULT_TEXT_SIZE_FRACTION),
    Large(0.07f),
    Huge(0.09f),
    ;

    val label: String
        get() = when (this) {
            System -> "System default"
            Small -> "Small"
            Normal -> "Normal"
            Large -> "Large"
            Huge -> "Very large"
        }
}

/**
 * What sits behind the subtitle text, which is what decides whether it can be read at all.
 *
 * White text over a bright scene disappears; every option other than [None] exists to stop that in a
 * different way. Text colour is deliberately not offered: white is the readable choice on video, and
 * a colour picker is a separate decision that would also need a contrast rule to be worth anything.
 */
enum class SubtitleBackground {
    System,
    None,
    Shadow,
    Outline,
    Box,
    ;

    val label: String
        get() = when (this) {
            System -> "System default"
            None -> "Plain text"
            Shadow -> "Drop shadow"
            Outline -> "Outlined"
            Box -> "Behind a box"
        }

    /** The colour drawn directly behind the glyphs. Transparent for everything but [Box]. */
    val backgroundColor: Int
        get() = if (this == Box) TRANSLUCENT_BLACK else TRANSPARENT
}

/** Media3's own default subtitle size, so **Normal** means what the player would have done anyway. */
const val MEDIA3_DEFAULT_TEXT_SIZE_FRACTION = 0.0533f

/**
 * Colours are plain ARGB ints rather than `android.graphics.Color` constants, so the rules above stay
 * on the JVM and testable.
 */
const val SUBTITLE_FOREGROUND_COLOR: Int = 0xFFFFFFFF.toInt()
const val SUBTITLE_EDGE_COLOR: Int = 0xFF000000.toInt()
private const val TRANSLUCENT_BLACK: Int = 0xCC000000.toInt()
private const val TRANSPARENT: Int = 0x00000000
