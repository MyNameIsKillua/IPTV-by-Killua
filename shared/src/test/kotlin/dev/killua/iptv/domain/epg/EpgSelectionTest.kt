package dev.killua.iptv.domain.epg

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.EpgEntry
import org.junit.Test

/**
 * Picking now and next out of a guide. All fixtures are fictitious.
 */
class EpgSelectionTest {
    @Test
    fun `the programme covering the moment is the current one`() {
        val entries = listOf(entry("Erstens", 0, 3_600), entry("Zweitens", 3_600, 7_200))

        assertThat(EpgSelection.nowPlaying(entries, 1_800)?.title).isEqualTo("Erstens")
        assertThat(EpgSelection.upNext(entries, 1_800)?.title).isEqualTo("Zweitens")
    }

    @Test
    fun `an entry ends exclusively so the boundary belongs to the next programme`() {
        val entries = listOf(entry("Erstens", 0, 3_600), entry("Zweitens", 3_600, 7_200))

        assertThat(EpgSelection.nowPlaying(entries, 3_600)?.title).isEqualTo("Zweitens")
    }

    @Test
    fun `a gap between programmes has nothing on but still knows what follows`() {
        val entries = listOf(entry("Erstens", 0, 3_600), entry("Zweitens", 5_400, 9_000))

        assertThat(EpgSelection.nowPlaying(entries, 4_000)).isNull()
        // Defined by start time, so a gap does not hide the next programme.
        assertThat(EpgSelection.upNext(entries, 4_000)?.title).isEqualTo("Zweitens")
    }

    @Test
    fun `overlapping entries resolve to the one that started last`() {
        // Providers do send overlaps; the later start is what a viewer is actually watching.
        val entries = listOf(entry("Vorlauf", 0, 7_200), entry("Aktuell", 3_600, 7_200))

        assertThat(EpgSelection.nowPlaying(entries, 4_000)?.title).isEqualTo("Aktuell")
    }

    @Test
    fun `a guide that has run out reports nothing rather than the last entry`() {
        val entries = listOf(entry("Vorbei", 0, 3_600))

        assertThat(EpgSelection.nowPlaying(entries, 9_000)).isNull()
        assertThat(EpgSelection.upNext(entries, 9_000)).isNull()
        assertThat(EpgSelection.progress(entries, 9_000)).isNull()
    }

    @Test
    fun `an empty guide is handled everywhere`() {
        assertThat(EpgSelection.nowPlaying(emptyList(), 1_000)).isNull()
        assertThat(EpgSelection.upNext(emptyList(), 1_000)).isNull()
        assertThat(EpgSelection.progress(emptyList(), 1_000)).isNull()
    }

    @Test
    fun `progress runs from zero to one across the current programme`() {
        val entries = listOf(entry("Laeuft", 0, 4_000))

        assertThat(EpgSelection.progress(entries, 0)).isEqualTo(0f)
        assertThat(EpgSelection.progress(entries, 1_000)).isEqualTo(0.25f)
        assertThat(EpgSelection.progress(entries, 3_999)).isWithin(0.001f).of(1f)
    }

    @Test
    fun `a zero-length entry reports no progress instead of dividing by zero`() {
        val entries = listOf(EpgEntry("Punkt", null, 1_000, 1_000))

        assertThat(EpgSelection.progress(entries, 1_000)).isNull()
    }

    private fun entry(title: String, start: Long, end: Long) =
        EpgEntry(title = title, description = null, startEpochSeconds = start, endEpochSeconds = end)
}
