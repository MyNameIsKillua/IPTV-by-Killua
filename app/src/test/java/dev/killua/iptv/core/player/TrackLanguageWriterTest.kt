package dev.killua.iptv.core.player

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.TrackLanguageSelection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The rules that decide whether a hand-made track choice reaches the store. All fixtures are
 * fictitious.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackLanguageWriterTest {
    private var stored = TrackLanguagePreferences()
    private val written = mutableListOf<TrackLanguagePreferences>()
    private var loads = 0

    @Test
    fun `a hand-picked language is written`() = runTest {
        val writer = createWriter()

        val accepted = writer.remember(TrackLanguageSelection(audioLanguage = "de"))
        testScheduler.advanceUntilIdle()

        assertThat(accepted).isTrue()
        assertThat(written).containsExactly(TrackLanguagePreferences(audioLanguage = "de"))
    }

    /**
     * The common case, and the one that matters for write volume: this runs on the same ten-second
     * rhythm as a watch-progress checkpoint, for the whole length of a film.
     */
    @Test
    fun `a viewer who never opened the track menu causes no store access at all`() = runTest {
        val writer = createWriter()

        repeat(50) { writer.remember(TrackLanguageSelection()) }
        testScheduler.advanceUntilIdle()

        assertThat(written).isEmpty()
        assertThat(loads).isEqualTo(0)
    }

    @Test
    fun `the same selection is only handled once`() = runTest {
        val writer = createWriter()

        assertThat(writer.remember(TrackLanguageSelection(audioLanguage = "de"))).isTrue()
        assertThat(writer.remember(TrackLanguageSelection(audioLanguage = "de"))).isFalse()
        testScheduler.advanceUntilIdle()

        assertThat(written).hasSize(1)
        assertThat(loads).isEqualTo(1)
    }

    @Test
    fun `a selection that repeats what is already stored is not written`() = runTest {
        stored = TrackLanguagePreferences(audioLanguage = "de")
        val writer = createWriter()

        writer.remember(TrackLanguageSelection(audioLanguage = "de"))
        testScheduler.advanceUntilIdle()

        assertThat(written).isEmpty()
    }

    @Test
    fun `a second track type is folded into what is already stored`() = runTest {
        stored = TrackLanguagePreferences(audioLanguage = "de")
        val writer = createWriter()

        writer.remember(TrackLanguageSelection(subtitleLanguage = "en"))
        testScheduler.advanceUntilIdle()

        assertThat(written).containsExactly(
            TrackLanguagePreferences(audioLanguage = "de", subtitleLanguage = "en"),
        )
    }

    /** Without the reset, re-picking what Settings just cleared would look like a duplicate. */
    @Test
    fun `after a reset the same selection is written again`() = runTest {
        val writer = createWriter()
        writer.remember(TrackLanguageSelection(audioLanguage = "de"))
        testScheduler.advanceUntilIdle()

        stored = TrackLanguagePreferences()
        writer.reset()
        assertThat(writer.remember(TrackLanguageSelection(audioLanguage = "de"))).isTrue()
        testScheduler.advanceUntilIdle()

        assertThat(written).hasSize(2)
    }

    @Test
    fun `a failing store never escapes the writer`() = runTest {
        val writer = TrackLanguageWriter(
            scope = TestScope(testScheduler),
            load = { stored },
            store = { error("the store is unavailable") },
        )

        writer.remember(TrackLanguageSelection(audioLanguage = "de"))
        testScheduler.advanceUntilIdle()

        assertThat(written).isEmpty()
    }

    private fun kotlinx.coroutines.test.TestScope.createWriter() = TrackLanguageWriter(
        scope = TestScope(testScheduler),
        load = {
            loads++
            stored
        },
        store = {
            stored = it
            written += it
        },
    )
}
