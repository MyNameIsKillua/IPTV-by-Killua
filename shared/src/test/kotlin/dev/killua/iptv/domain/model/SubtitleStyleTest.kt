package dev.killua.iptv.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleStyleTest {

    /** The default has to be the platform's, or an accessibility setting would be overridden. */
    @Test
    fun `the default defers to the system on both halves`() {
        val style = SubtitleStyle()

        assertThat(style.textSize).isEqualTo(SubtitleTextSize.System)
        assertThat(style.background).isEqualTo(SubtitleBackground.System)
        assertThat(style.isDefault).isTrue()
        assertThat(style.appliesEmbeddedStyles).isTrue()
        assertThat(style.appliesEmbeddedFontSizes).isTrue()
    }

    @Test
    fun `one chosen half is enough to stop being the default`() {
        assertThat(SubtitleStyle(textSize = SubtitleTextSize.Large).isDefault).isFalse()
        assertThat(SubtitleStyle(background = SubtitleBackground.Box).isDefault).isFalse()
    }

    /** Otherwise the setting would look broken on exactly the streams that carry styling. */
    @Test
    fun `a chosen background stops the stream's own styling from winning`() {
        val style = SubtitleStyle(background = SubtitleBackground.Outline)

        assertThat(style.appliesEmbeddedStyles).isFalse()
    }

    @Test
    fun `a chosen size stops the stream's own font sizes from winning`() {
        val style = SubtitleStyle(textSize = SubtitleTextSize.Huge)

        assertThat(style.appliesEmbeddedFontSizes).isFalse()
        // The background is still the system's, so the stream may still colour its own text.
        assertThat(style.appliesEmbeddedStyles).isTrue()
    }

    @Test
    fun `only the system size carries no fraction`() {
        assertThat(SubtitleTextSize.System.fraction).isNull()
        val explicit = SubtitleTextSize.entries - SubtitleTextSize.System
        assertThat(explicit.mapNotNull { it.fraction }).hasSize(explicit.size)
    }

    @Test
    fun `the sizes are ordered smallest to largest`() {
        val fractions = SubtitleTextSize.entries.mapNotNull { it.fraction }

        assertThat(fractions).isInOrder()
    }

    /** `Normal` must mean "what the player would have done", not a value this app invented. */
    @Test
    fun `normal is Media3's own default size`() {
        assertThat(SubtitleTextSize.Normal.fraction).isEqualTo(MEDIA3_DEFAULT_TEXT_SIZE_FRACTION)
    }

    @Test
    fun `only the box draws anything behind the glyphs`() {
        assertThat(SubtitleBackground.Box.backgroundColor and OPAQUE_ALPHA).isNotEqualTo(0)
        val transparent = SubtitleBackground.entries - SubtitleBackground.Box
        assertThat(transparent.map { it.backgroundColor }.toSet()).containsExactly(0)
    }

    @Test
    fun `every option is named for a menu`() {
        assertThat(SubtitleTextSize.entries.map { it.label }.filter { it.isBlank() }).isEmpty()
        assertThat(SubtitleBackground.entries.map { it.label }.filter { it.isBlank() }).isEmpty()
        assertThat(SubtitleTextSize.entries.map { it.label }.distinct())
            .hasSize(SubtitleTextSize.entries.size)
        assertThat(SubtitleBackground.entries.map { it.label }.distinct())
            .hasSize(SubtitleBackground.entries.size)
    }

    private companion object {
        const val OPAQUE_ALPHA = 0xFF000000.toInt()
    }
}
