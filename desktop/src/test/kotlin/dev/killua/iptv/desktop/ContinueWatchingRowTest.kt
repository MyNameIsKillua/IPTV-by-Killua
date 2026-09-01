package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.userdata.EPISODE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.ProgressRecord
import org.junit.Test

/**
 * Turning a stored position back into something with a name on it.
 *
 * This is where a half-watched episode carried over from the phone used to disappear. A film or a
 * series can be looked up by its id in the listing this client holds; an **episode cannot** — no
 * Xtream listing indexes episodes, and resolving one means asking `get_series_info` about every
 * series in the library. So the export now carries the series id beside the episode's own, and this
 * is what reads it.
 */
class ContinueWatchingRowTest {

    private val library = LibraryIndex(
        movies = listOf(
            MovieSummary("501", "1", "Taran und der Zauberkessel", null, "mkv", null, null, null, 0),
        ),
        series = listOf(
            SeriesSummary("400", "2", "DE - Mr. Robot (2015)", "https://art.example/robot.jpg", 8.0, 2015, null, 0),
        ),
        loaded = setOf(LibraryKind.Movies, LibraryKind.Series),
    )

    @Test
    fun `an episode is named after its series`() {
        val record = progress(EPISODE_CONTENT_TYPE, "9001", seriesId = "400")

        val row = record.asIndexed(titles = emptyMap(), library = library)

        assertThat(row).isNotNull()
        assertThat(row!!.label).isEqualTo("DE - Mr. Robot (2015)")
        assertThat(row.artworkUrl).isEqualTo("https://art.example/robot.jpg")
        // It still plays the episode: what the row is *called* comes from the series, what it
        // *starts* is the position that was stored.
        assertThat(row.id).isEqualTo("9001")
        assertThat(row.contentType).isEqualTo(EPISODE_CONTENT_TYPE)
    }

    @Test
    fun `an episode from a file written before the field existed is left out rather than guessed at`() {
        val record = progress(EPISODE_CONTENT_TYPE, "9001", seriesId = null)

        assertThat(record.asIndexed(emptyMap(), library)).isNull()
    }

    @Test
    fun `an episode whose series is not in this library is left out`() {
        // The series listing was skipped, or the provider has dropped the show. Either way there is
        // no name to put on the tile, and a row reading "9001" would be worse than no row.
        val record = progress(EPISODE_CONTENT_TYPE, "9001", seriesId = "nope")

        assertThat(record.asIndexed(emptyMap(), library)).isNull()
    }

    @Test
    fun `a film is still looked up by its own id`() {
        val record = progress(MOVIE_CONTENT_TYPE, "501")

        assertThat(record.asIndexed(emptyMap(), library)?.label)
            .isEqualTo("Taran und der Zauberkessel")
    }

    @Test
    fun `a name this client has already shown wins over the listing`() {
        // The title cache is what was on screen here; it is the more specific answer, and for an
        // episode it is the only one that can name the episode rather than the show.
        val titles = mapOf(
            TitleIndex.keyOf(EPISODE_CONTENT_TYPE, "9001") to
                IndexedTitle("S1 E9 · eps1.8_m1rr0r1ng.qt", null, "mkv"),
        )

        val row = progress(EPISODE_CONTENT_TYPE, "9001", seriesId = "400").asIndexed(titles, library)

        assertThat(row?.label).isEqualTo("S1 E9 · eps1.8_m1rr0r1ng.qt")
    }

    private fun progress(type: String, id: String, seriesId: String? = null) = ProgressRecord(
        contentType = type,
        contentId = id,
        positionMs = 60_000L,
        durationMs = 1_800_000L,
        completed = false,
        updatedAtEpochMillis = 10L,
        seriesId = seriesId,
    )
}
