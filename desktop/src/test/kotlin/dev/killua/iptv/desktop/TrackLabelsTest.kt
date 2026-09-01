package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackLabelsTest {

    @Test
    fun `a known language is what the menu says`() {
        val labels = readableTrackLabels(
            listOf(
                TrackOption(1, "Track 1 - [Deutsch]", "ger"),
                TrackOption(2, "Track 2 - [English]", "eng"),
            ),
        )

        // The container's spelling is the muxer's business; the language is what a viewer chooses.
        assertThat(labels).containsExactly("German", "English").inOrder()
    }

    @Test
    fun `two tracks in one language keep the container's description`() {
        val labels = readableTrackLabels(
            listOf(
                TrackOption(1, "Stereo", "ger"),
                TrackOption(2, "5.1", "ger"),
                TrackOption(3, "Commentary", "eng"),
            ),
        )

        // "German" twice would be a menu that cannot be used. English stays short, because it is
        // not ambiguous.
        assertThat(labels).containsExactly("German · Stereo", "German · 5.1", "English").inOrder()
    }

    @Test
    fun `a track without a language keeps what libvlc said`() {
        val labels = readableTrackLabels(
            listOf(
                TrackOption(-1, "Disable", null),
                TrackOption(1, "Track 1", null),
            ),
        )

        // Disable is a control rather than a track, and an unlabelled track has nothing better to
        // be called than what the container said.
        assertThat(labels).containsExactly("Disable", "Track 1").inOrder()
    }

    @Test
    fun `an unlabelled track is not padded with its own description`() {
        val labels = readableTrackLabels(
            listOf(
                TrackOption(1, "Track 1", null),
                TrackOption(2, "Track 1", null),
            ),
        )

        // Two tracks a container named identically are genuinely indistinguishable; repeating the
        // name after itself would only look like a bug.
        assertThat(labels).containsExactly("Track 1", "Track 1").inOrder()
    }

    @Test
    fun `an empty list stays empty`() {
        assertThat(readableTrackLabels(emptyList())).isEmpty()
    }
}
