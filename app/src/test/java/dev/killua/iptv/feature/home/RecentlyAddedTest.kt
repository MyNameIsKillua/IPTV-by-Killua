package dev.killua.iptv.feature.home

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.ResumableKind
import org.junit.Test

/**
 * Whether a provider's "added" timestamps are worth ordering by.
 *
 * This is the whole reason the row was not built earlier. A provider that stamps every title with
 * one value turns "Recently added" into an arbitrary slice of the library wearing that label, and
 * the viewer has no way to tell the two apart.
 */
class RecentlyAddedTest {
    @Test
    fun `both libraries are mixed and ordered newest first`() {
        val row = RecentlyAdded.rowOf(
            movies = listOf(movie("501", 300), movie("502", 100)),
            series = listOf(series("7", 400), series("8", 200)),
            limit = 10,
        )

        assertThat(row.map { it.contentId }).containsExactly("7", "501", "8", "502").inOrder()
    }

    @Test
    fun `a library whose timestamps are all identical is left out entirely`() {
        // The provider stamped its whole import with one value: the order it implies is noise.
        val row = RecentlyAdded.rowOf(
            movies = listOf(movie("501", 100), movie("502", 100), movie("503", 100)),
            series = listOf(series("7", 400), series("8", 200)),
            limit = 10,
        )

        assertThat(row.map { it.contentId }).containsExactly("7", "8").inOrder()
    }

    @Test
    fun `when neither library says anything the row is empty rather than arbitrary`() {
        val row = RecentlyAdded.rowOf(
            movies = listOf(movie("501", 100), movie("502", 100)),
            series = listOf(series("7", 900), series("8", 900)),
            limit = 10,
        )

        assertThat(row).isEmpty()
    }

    @Test
    fun `a single title cannot establish an order and is left out`() {
        // One row proves nothing about whether the field is maintained.
        val row = RecentlyAdded.rowOf(
            movies = listOf(movie("501", 100)),
            series = emptyList(),
            limit = 10,
        )

        assertThat(row).isEmpty()
    }

    @Test
    fun `the row is trimmed to its limit after mixing, not before`() {
        val row = RecentlyAdded.rowOf(
            movies = listOf(movie("501", 10), movie("502", 40)),
            series = listOf(series("7", 30), series("8", 20)),
            limit = 2,
        )

        // Trimming each library first would have kept 502 and 7 only by accident.
        assertThat(row.map { it.contentId }).containsExactly("502", "7").inOrder()
    }

    @Test
    fun `an empty input and a limit of nothing are both handled`() {
        assertThat(RecentlyAdded.rowOf(emptyList(), emptyList(), limit = 10)).isEmpty()
        assertThat(
            RecentlyAdded.rowOf(
                movies = listOf(movie("501", 100), movie("502", 200)),
                series = emptyList(),
                limit = 0,
            ),
        ).isEmpty()
    }

    private fun movie(id: String, addedAt: Long) = entry(id, ResumableKind.Movie, addedAt)
    private fun series(id: String, addedAt: Long) = entry(id, ResumableKind.Series, addedAt)

    private fun entry(id: String, kind: ResumableKind, addedAt: Long) = RecentlyAddedEntry(
        contentId = id,
        kind = kind,
        title = "Titel $id",
        posterUrl = null,
        addedAtEpochSeconds = addedAt,
    )
}
