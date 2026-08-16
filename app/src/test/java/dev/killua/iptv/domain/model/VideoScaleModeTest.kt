package dev.killua.iptv.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The picture-size control cycles rather than opening a menu, so the order it walks and the fact
 * that it returns to the start are the behaviour, not an implementation detail.
 */
class VideoScaleModeTest {
    @Test
    fun `the control walks fit, zoom, stretch and back`() {
        assertThat(VideoScaleMode.Fit.next()).isEqualTo(VideoScaleMode.Zoom)
        assertThat(VideoScaleMode.Zoom.next()).isEqualTo(VideoScaleMode.Fill)
        assertThat(VideoScaleMode.Fill.next()).isEqualTo(VideoScaleMode.Fit)
    }

    @Test
    fun `cycling from anywhere reaches every mode and returns to where it started`() {
        VideoScaleMode.entries.forEach { start ->
            val walked = generateSequence(start) { it.next() }
                .drop(1)
                .take(VideoScaleMode.entries.size)
                .toList()

            assertThat(walked).containsExactlyElementsIn(VideoScaleMode.entries)
            assertThat(walked.last()).isEqualTo(start)
        }
    }

    @Test
    fun `the honest mode is first, so a fresh install letterboxes rather than crops`() {
        assertThat(VideoScaleMode.entries.first()).isEqualTo(VideoScaleMode.Fit)
    }

    @Test
    fun `every mode names itself, since two of them look alike on an unknown stream`() {
        assertThat(VideoScaleMode.Fit.label).isEqualTo("Fit")
        assertThat(VideoScaleMode.Zoom.label).isEqualTo("Zoom")
        // Deliberately not "Fill": what it does to the picture is stretch it.
        assertThat(VideoScaleMode.Fill.label).isEqualTo("Stretch")
        assertThat(VideoScaleMode.entries.map { it.label }.toSet()).hasSize(3)
    }

    @Test
    fun `names are stable, because the stored preference is the name`() {
        // Renaming a constant would silently reset everyone's saved choice to Fit.
        assertThat(VideoScaleMode.entries.map { it.name })
            .containsExactly("Fit", "Zoom", "Fill").inOrder()
    }
}
