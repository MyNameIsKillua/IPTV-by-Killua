package dev.killua.iptv.feature.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import dev.killua.iptv.domain.model.SUBTITLE_EDGE_COLOR
import dev.killua.iptv.domain.model.SUBTITLE_FOREGROUND_COLOR
import dev.killua.iptv.domain.model.SubtitleBackground
import dev.killua.iptv.domain.model.SubtitleStyle
import dev.killua.iptv.domain.model.SubtitleTextSize

/**
 * Applies a [SubtitleStyle] to the player's own `SubtitleView`.
 *
 * The decisions are in `domain/model/SubtitleStyle.kt`, which is plain Kotlin and tested. This is
 * only the translation, and it cannot run on the JVM: `SubtitleView` and `CaptionStyleCompat` are
 * Android views and unstable Media3 API respectively.
 *
 * Safe to call on every recomposition. Each call sets every property it owns, so no earlier choice
 * can survive as a leftover — the view is long-lived and an episode change reuses it.
 */
@OptIn(UnstableApi::class)
fun PlayerView.applySubtitleStyle(style: SubtitleStyle) {
    val view = subtitleView ?: return
    view.setApplyEmbeddedStyles(style.appliesEmbeddedStyles)
    view.setApplyEmbeddedFontSizes(style.appliesEmbeddedFontSizes)
    view.applyTextSize(style.textSize)
    view.applyBackground(style.background)
}

/**
 * `setUserDefaultTextSize` reads Android's caption size, including the multiplier set under
 * Accessibility. An explicit choice is fractional so it follows the picture rather than the device.
 */
@OptIn(UnstableApi::class)
private fun SubtitleView.applyTextSize(size: SubtitleTextSize) {
    val fraction = size.fraction
    if (fraction == null) {
        setUserDefaultTextSize()
    } else {
        setFractionalTextSize(fraction)
    }
}

/**
 * Everything but the box relies on the **edge** to separate white text from a bright scene, which is
 * what the platform's own caption styles do too. The window colour stays transparent throughout: a
 * band across the whole subtitle region hides more of the picture than the text needs.
 */
@OptIn(UnstableApi::class)
private fun SubtitleView.applyBackground(background: SubtitleBackground) {
    if (background == SubtitleBackground.System) {
        setUserDefaultStyle()
        return
    }
    val edgeType = when (background) {
        SubtitleBackground.Shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleBackground.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        else -> CaptionStyleCompat.EDGE_TYPE_NONE
    }
    setStyle(
        CaptionStyleCompat(
            SUBTITLE_FOREGROUND_COLOR,
            background.backgroundColor,
            WINDOW_COLOR,
            edgeType,
            SUBTITLE_EDGE_COLOR,
            /* typeface = */ null,
        ),
    )
}

/** Transparent by decision rather than by an unnamed zero; see [applyBackground]. */
private const val WINDOW_COLOR = 0x00000000
