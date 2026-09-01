package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * When the controls over the picture are allowed to disappear.
 *
 * The rule is small and the cost of getting it wrong is not: chrome that hides while someone is
 * reaching for it, or chrome that never hides and sits across a film in fullscreen — which is what
 * the owner reported.
 */
class PlayerChromeTest {

    @Test
    fun `a moving picture gets the screen to itself`() {
        assertThat(
            chromeMayHide(isPlaying = true, hasFailure = false, switching = false, hasPicture = true),
        ).isTrue()
    }

    @Test
    fun `a paused film keeps its controls`() {
        // Someone stopped it on purpose. Taking the controls away from them is the client deciding
        // they are finished with it.
        assertThat(
            chromeMayHide(isPlaying = false, hasFailure = false, switching = false, hasPicture = true),
        ).isFalse()
    }

    @Test
    fun `a failure keeps its message and its Try again`() {
        assertThat(
            chromeMayHide(isPlaying = true, hasFailure = true, switching = false, hasPicture = true),
        ).isFalse()
    }

    @Test
    fun `an open switch panel is a list being read`() {
        assertThat(
            chromeMayHide(isPlaying = true, hasFailure = false, switching = true, hasPicture = true),
        ).isFalse()
    }

    @Test
    fun `nothing hides over a title that has not started`() {
        // Before the first frame there is a spinner rather than a picture, and hiding the way back
        // would leave a viewer alone with a black rectangle.
        assertThat(
            chromeMayHide(isPlaying = true, hasFailure = false, switching = false, hasPicture = false),
        ).isFalse()
    }
}
