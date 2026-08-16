package dev.killua.iptv.core.preferences

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackGestureOptionsTest {
    @Test
    fun `seek choices match the supported discrete intervals`() {
        assertThat(PlaybackGestureOptions.seekSeconds)
            .containsExactly(5, 10, 15, 20, 30, 45, 60)
            .inOrder()
    }

    @Test
    fun `hold speed choices match the supported discrete speeds`() {
        assertThat(PlaybackGestureOptions.holdSpeeds)
            .containsExactly(1.25f, 1.5f, 1.75f, 2f)
            .inOrder()
    }

    @Test
    fun `valid seek value is preserved`() {
        assertThat(PlaybackGestureOptions.validSeekSeconds(45)).isEqualTo(45)
    }

    @Test
    fun `missing or corrupt seek value uses ten second default`() {
        assertThat(PlaybackGestureOptions.validSeekSeconds(null)).isEqualTo(10)
        assertThat(PlaybackGestureOptions.validSeekSeconds(9)).isEqualTo(10)
        assertThat(PlaybackGestureOptions.validSeekSeconds(3_600)).isEqualTo(10)
    }

    @Test
    fun `valid hold speed is preserved`() {
        assertThat(PlaybackGestureOptions.validHoldSpeedHundredths(175)).isEqualTo(175)
    }

    @Test
    fun `missing or corrupt hold speed uses two times default`() {
        assertThat(PlaybackGestureOptions.validHoldSpeedHundredths(null)).isEqualTo(200)
        assertThat(PlaybackGestureOptions.validHoldSpeedHundredths(100)).isEqualTo(200)
        assertThat(PlaybackGestureOptions.validHoldSpeedHundredths(800)).isEqualTo(200)
    }
}
