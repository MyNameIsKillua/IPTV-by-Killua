package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import org.junit.Test

/**
 * The library held in memory, and the two things it exists to make possible.
 *
 * Browsing without picking a category first, and a search that can find a title without being told
 * which shelf it is on. Neither was reachable while the client asked for one category at a time, so
 * everything here is about the rules that replaced that — not about the request, which is somebody
 * else's test.
 */
class LibraryIndexTest {

    private val index = LibraryIndex(
        channels = listOf(
            channel("1", "DE | Sky Sport 1", category = "10"),
            channel("2", "DE | ZDF HD", category = "10"),
            channel("3", "UK | Sky Sports Main Event", category = "20"),
        ),
        movies = listOf(
            movie("100", "The Office Party", category = "50", added = 300L),
            movie("101", "Office Space", category = "50", added = 200L),
            movie("102", "Der Astronaut", category = "51", added = 100L),
        ),
        series = listOf(
            series("200", "The Office", category = "60", modified = 400L),
            series("201", "Avatar: The Last Airbender", category = "61", modified = 50L),
        ),
        loaded = LibraryKind.entries.toSet(),
    )

    @Test
    fun `a category is a filter over what is already held`() {
        // The whole point of the reversal: opening a category costs no request at all now.
        assertThat(index.channelsIn("10").map { it.id }).containsExactly("1", "2")
        assertThat(index.moviesIn("51").map { it.id }).containsExactly("102")
        assertThat(index.seriesIn("60").map { it.id }).containsExactly("200")
    }

    @Test
    fun `a category and a typed word narrow in one pass`() {
        // What the filter box over a library does. Both narrowings at once, against names the index
        // folded when the listing arrived rather than on every keystroke.
        assertThat(index.channelsIn("10", "zdf").map { it.id }).containsExactly("2")
        assertThat(index.moviesIn("50", "space").map { it.id }).containsExactly("101")
        // The term alone reaches across categories.
        assertThat(index.channelsIn(query = "sky").map { it.id }).containsExactly("1", "3")
    }

    @Test
    fun `nothing to narrow by hands back the listing itself`() {
        // A hundred thousand titles copied on every recomposition would be the cost of being tidy
        // here. This is the common case: a library opened with no category and nothing typed.
        assertThat(index.moviesIn()).isSameInstanceAs(index.movies)
        assertThat(index.channelsIn(null, "  ")).isSameInstanceAs(index.channels)
    }

    @Test
    fun `one letter is not a search`() {
        // A single character matches a third of a library. That is a redraw, not a result, and the
        // phone refuses it for the same reason.
        assertThat(index.search("o").isEmpty).isTrue()
        assertThat(index.search(" ").isEmpty).isTrue()
        assertThat(index.search("of").isEmpty).isFalse()
    }

    @Test
    fun `it searches every library at once`() {
        val hits = index.search("office")

        assertThat(hits.movies.map { it.id }).containsExactly("100", "101")
        assertThat(hits.series.map { it.id }).containsExactly("200")
        assertThat(hits.channels).isEmpty()
    }

    @Test
    fun `a title that starts with the term comes first`() {
        // Someone typing "office" means the one called that, not the one with it in the middle.
        val hits = index.search("office")

        assertThat(hits.movies.first().id).isEqualTo("101")
    }

    @Test
    fun `punctuation and case are folded on both sides`() {
        // The shared normalizer, so what is findable here is findable on the phone.
        assertThat(index.search("avatar the last").series.map { it.id }).containsExactly("201")
        assertThat(index.search("SKY SPORT").channels.map { it.id }).containsExactly("1", "3")
    }

    @Test
    fun `it says how many it found even when it shows fewer`() {
        val many = LibraryIndex(
            movies = (1..40).map { movie(it.toString(), "Matrix $it") },
            loaded = setOf(LibraryKind.Movies),
        )

        val hits = many.search("matrix", limitPerKind = 5)

        assertThat(hits.movies).hasSize(5)
        // Without the total, "5 results" would be a lie about a library with forty of them, and
        // nothing would tell the viewer that typing more would help.
        assertThat(hits.totalOf(LibraryKind.Movies)).isEqualTo(40)
    }

    @Test
    fun `a listing that was never read finds nothing rather than pretending`() {
        val partial = LibraryIndex(
            movies = listOf(movie("1", "Office Space")),
            loaded = setOf(LibraryKind.Movies),
        )

        assertThat(partial.has(LibraryKind.Movies)).isTrue()
        assertThat(partial.has(LibraryKind.Series)).isFalse()
        assertThat(partial.search("office").series).isEmpty()
    }

    @Test
    fun `stored ids can be turned back into titles`() {
        // What My list is built on: the export carries ids and the listing carries names.
        assertThat(index.movie("102")?.name).isEqualTo("Der Astronaut")
        assertThat(index.series("200")?.name).isEqualTo("The Office")
        assertThat(index.channel("3")?.name).contains("Sky Sports")
        assertThat(index.movie("nope")).isNull()
    }

    @Test
    fun `recently added merges both libraries by their own timestamps`() {
        val row = index.recentlyAdded()

        // Series 200 is stamped 400, film 100 is 300, film 101 is 200, film 102 is 100, and
        // series 201 is 50 — one row, ordered by the timestamps rather than by which library.
        assertThat(row.map { it.contentId })
            .containsExactly("200", "100", "101", "102", "201").inOrder()
    }

    @Test
    fun `a provider that stamps everything at once gets no recently-added row`() {
        // The shared rule. Ordering by a column where every row is identical produces an arbitrary
        // slice of the library wearing a label, which is worse than showing nothing.
        val flat = LibraryIndex(
            movies = (1..5).map { movie(it.toString(), "Film $it", added = 999L) },
            loaded = setOf(LibraryKind.Movies),
        )

        assertThat(flat.recentlyAdded()).isEmpty()
    }

    @Test
    fun `a title with no timestamp is left out rather than dated`() {
        val partial = LibraryIndex(
            movies = listOf(
                movie("1", "Dated", added = 10L),
                movie("2", "Undated", added = null),
                movie("3", "Also dated", added = 20L),
            ),
            loaded = setOf(LibraryKind.Movies),
        )

        assertThat(partial.recentlyAdded().map { it.contentId }).containsExactly("3", "1").inOrder()
    }

    private fun channel(id: String, name: String, category: String? = null) = LiveChannel(
        id = id,
        categoryId = category,
        name = name,
        logoUrl = null,
        epgChannelId = null,
        containerExtension = null,
        directSource = null,
        providerOrder = 0,
    )

    private fun movie(
        id: String,
        name: String,
        category: String? = null,
        added: Long? = null,
    ) = MovieSummary(
        id = id,
        categoryId = category,
        name = name,
        posterUrl = null,
        containerExtension = "mkv",
        rating = null,
        releaseYear = null,
        addedAtEpochSeconds = added,
        providerOrder = 0,
    )

    private fun series(
        id: String,
        name: String,
        category: String? = null,
        modified: Long? = null,
    ) = SeriesSummary(
        id = id,
        categoryId = category,
        name = name,
        posterUrl = null,
        rating = null,
        releaseYear = null,
        lastModifiedEpochSeconds = modified,
        providerOrder = 0,
    )
}
