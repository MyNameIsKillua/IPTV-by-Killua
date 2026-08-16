package dev.killua.iptv.core.player

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whether a player screen that has already been replaced can still speak for the Activity.
 *
 * It could, and that is what switched Picture-in-Picture off after every episode change: moving to
 * the next episode replaces the player screen, and Compose disposes the outgoing one *after* the
 * incoming one is running, so the old screen's `hide` arrived last and won.
 */
class PlayerPresentationStateTest {
    @Test
    fun `a screen that has been replaced cannot hide its successor`() {
        val state = PlayerPresentationState()
        val leaving = Any()
        val arriving = Any()
        state.show(leaving, PLAYING)

        state.show(arriving, PLAYING)
        state.hide(leaving)

        assertThat(state.state.value.isPlayerScreenVisible).isTrue()
        assertThat(state.state.value.isPlaying).isTrue()
    }

    @Test
    fun `a replaced screen cannot report playback either`() {
        val state = PlayerPresentationState()
        val leaving = Any()
        val arriving = Any()
        state.show(leaving, PLAYING)
        state.show(arriving, PLAYING)

        // The outgoing screen's own player is stopping; that must not describe the new one.
        state.update(leaving, PlaybackSnapshot(isPlaying = false))

        assertThat(state.state.value.isPlaying).isTrue()
    }

    @Test
    fun `the current screen still hides normally`() {
        val state = PlayerPresentationState()
        val screen = Any()
        state.show(screen, PLAYING)

        state.hide(screen)

        assertThat(state.state.value.isPlayerScreenVisible).isFalse()
    }

    @Test
    fun `the current screen's own updates are carried through`() {
        val state = PlayerPresentationState()
        val screen = Any()
        state.show(screen, PlaybackSnapshot())

        state.update(screen, PLAYING.copy(videoWidth = 1920, videoHeight = 1080))

        assertThat(state.state.value.isPlaying).isTrue()
        assertThat(state.state.value.isVideoReady).isTrue()
        assertThat(state.state.value.aspectWidth).isEqualTo(1920)
        assertThat(state.state.value.aspectHeight).isEqualTo(1080)
    }

    @Test
    fun `an update after the last screen has gone does not bring the player back`() {
        val state = PlayerPresentationState()
        val screen = Any()
        state.show(screen, PLAYING)
        state.hide(screen)

        state.update(screen, PLAYING)

        assertThat(state.state.value.isPlayerScreenVisible).isFalse()
    }

    private companion object {
        val PLAYING = PlaybackSnapshot(
            isPlaying = true,
            playWhenReady = true,
            playbackState = Player.STATE_READY,
        )
    }
}
