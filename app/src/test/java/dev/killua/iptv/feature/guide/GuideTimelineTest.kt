package dev.killua.iptv.feature.guide

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.EpgEntry
import org.junit.Test

/**
 * Where programmes land on the guide's shared time axis. Every row draws against the same window,
 * so this arithmetic decides whether the columns line up at all — and providers are careless enough
 * with these timestamps that most of these cases come from real payload shapes.
 */
class GuideTimelineTest {
    @Test
    fun `a programme filling the window spans it exactly`() {
        val slots = guideSlots(listOf(entry(START, START + WINDOW)), START, START + WINDOW)

        assertThat(slots).hasSize(1)
        assertThat(slots.single().startFraction).isWithin(TOLERANCE).of(0f)
        assertThat(slots.single().endFraction).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun `a programme already running is clipped to the left edge, not dropped`() {
        // The film started an hour before the window; what is left of it must still be visible.
        val slots = guideSlots(
            listOf(entry(START - HOUR, START + HOUR)),
            START,
            START + WINDOW,
        )

        assertThat(slots.single().startFraction).isWithin(TOLERANCE).of(0f)
        assertThat(slots.single().endFraction).isWithin(TOLERANCE).of(0.25f)
    }

    @Test
    fun `a programme running past the window is clipped to the right edge`() {
        val slots = guideSlots(
            listOf(entry(START + 3 * HOUR, START + 9 * HOUR)),
            START,
            START + WINDOW,
        )

        assertThat(slots.single().startFraction).isWithin(TOLERANCE).of(0.75f)
        assertThat(slots.single().endFraction).isWithin(TOLERANCE).of(1f)
    }

    @Test
    fun `programmes entirely outside the window are left out`() {
        val slots = guideSlots(
            listOf(
                entry(START - 4 * HOUR, START - HOUR),
                entry(START + 5 * HOUR, START + 6 * HOUR),
            ),
            START,
            START + WINDOW,
        )

        assertThat(slots).isEmpty()
    }

    @Test
    fun `an entry touching an edge exactly contributes nothing rather than a sliver`() {
        val slots = guideSlots(
            listOf(
                entry(START - HOUR, START),
                entry(START + WINDOW, START + WINDOW + HOUR),
            ),
            START,
            START + WINDOW,
        )

        assertThat(slots).isEmpty()
    }

    @Test
    fun `entries arriving out of order come back in time order`() {
        val slots = guideSlots(
            listOf(
                entry(START + 2 * HOUR, START + 3 * HOUR, "Third"),
                entry(START, START + HOUR, "First"),
                entry(START + HOUR, START + 2 * HOUR, "Second"),
            ),
            START,
            START + WINDOW,
        )

        assertThat(slots.map { it.entry.title })
            .containsExactly("First", "Second", "Third").inOrder()
    }

    @Test
    fun `an entry that ends before it starts is dropped rather than drawn backwards`() {
        val slots = guideSlots(
            listOf(entry(START + 2 * HOUR, START + HOUR), entry(START, START)),
            START,
            START + WINDOW,
        )

        assertThat(slots).isEmpty()
    }

    @Test
    fun `a window with no length produces nothing instead of dividing by zero`() {
        assertThat(guideSlots(listOf(entry(START, START + HOUR)), START, START)).isEmpty()
        assertThat(guideHourMarks(START, START)).isEmpty()
        assertThat(guideFractionOf(START, START, START)).isNull()
    }

    @Test
    fun `the window opens on the previous half hour so the current programme is not cut off`() {
        // 20:47 rounds back to 20:30, not forward and not to the hour.
        assertThat(guideWindowStart(1_755_368_820L)).isEqualTo(1_755_368_820L / 1800L * 1800L)
        assertThat(guideWindowStart(START + 1800L)).isEqualTo(START + 1800L)
        assertThat(guideWindowStart(START + 1799L)).isEqualTo(START)
    }

    @Test
    fun `the ruler marks whole hours inside the window only`() {
        val marks = guideHourMarks(START + 1800L, START + 1800L + WINDOW)

        assertThat(marks).hasSize(4)
        assertThat(marks.first()).isEqualTo(START + HOUR)
        marks.forEach { assertThat(it % HOUR).isEqualTo(0L) }
    }

    @Test
    fun `the now marker sits inside the window and nowhere outside it`() {
        assertThat(guideFractionOf(START + HOUR, START, START + WINDOW))
            .isWithin(TOLERANCE).of(0.25f)
        assertThat(guideFractionOf(START - 1L, START, START + WINDOW)).isNull()
        assertThat(guideFractionOf(START + WINDOW + 1L, START, START + WINDOW)).isNull()
    }

    private fun entry(start: Long, end: Long, title: String = "Sendung") = EpgEntry(
        title = title,
        description = null,
        startEpochSeconds = start,
        endEpochSeconds = end,
    )

    private companion object {
        const val TOLERANCE = 0.0001f
        const val HOUR = 3_600L
        const val WINDOW = GUIDE_WINDOW_SECONDS
        // A whole hour, so the fractions in these cases are readable.
        const val START = 1_755_360_000L
    }
}
