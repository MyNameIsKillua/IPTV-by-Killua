package dev.killua.iptv.domain.browse

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveSortOrder
import dev.killua.iptv.domain.model.MovieSortOrder
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSortOrder
import dev.killua.iptv.domain.model.SeriesSummary
import org.junit.Test

class BrowseOrderingTest {

    @Test
    fun `the provider's own order is the default and is exactly its order`() {
        val films = listOf(movie("c", order = 2), movie("a", order = 0), movie("b", order = 1))

        assertThat(films.orderedBy(MovieSortOrder.ProviderDefault).map { it.name })
            .containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `names sort without regard to case`() {
        val films = listOf(movie("banana", order = 0), movie("Apple", order = 1))

        // "Apple" after "banana" would be the answer if capitals sorted before lower case, which is
        // what a naive comparison gives and what nobody expects from an alphabetical list.
        assertThat(films.orderedBy(MovieSortOrder.NameAscending).map { it.name })
            .containsExactly("Apple", "banana").inOrder()
    }

    @Test
    fun `an unrated film sorts last rather than as zero`() {
        val films = listOf(
            movie("unrated", order = 0, rating = null),
            movie("poor", order = 1, rating = 2.0),
            movie("good", order = 2, rating = 8.5),
        )

        assertThat(films.orderedBy(MovieSortOrder.RatingDescending).map { it.name })
            .containsExactly("good", "poor", "unrated").inOrder()
    }

    @Test
    fun `a film with no year or added date also sorts last`() {
        val films = listOf(
            movie("unknown", order = 0),
            movie("old", order = 1, year = 1994, added = 100L),
            movie("new", order = 2, year = 2024, added = 900L),
        )

        assertThat(films.orderedBy(MovieSortOrder.ReleaseYearDescending).map { it.name })
            .containsExactly("new", "old", "unknown").inOrder()
        assertThat(films.orderedBy(MovieSortOrder.RecentlyAdded).map { it.name })
            .containsExactly("new", "old", "unknown").inOrder()
    }

    @Test
    fun `equal values keep the provider's order rather than an arbitrary one`() {
        val films = listOf(
            movie("second", order = 5, rating = 7.0),
            movie("first", order = 1, rating = 7.0),
        )

        // Two titles the provider rated equally must not swap places between one visit and the next.
        assertThat(films.orderedBy(MovieSortOrder.RatingDescending).map { it.name })
            .containsExactly("first", "second").inOrder()
    }

    @Test
    fun `ordering never loses or invents an entry`() {
        val films = List(20) { movie("film $it", order = it, rating = if (it % 3 == 0) null else 5.0) }

        MovieSortOrder.entries.forEach { order ->
            assertThat(films.orderedBy(order)).containsExactlyElementsIn(films)
        }
    }

    @Test
    fun `series order by when the provider last touched them`() {
        val shows = listOf(
            series("stale", order = 0, modified = 100L),
            series("never", order = 1, modified = null),
            series("fresh", order = 2, modified = 900L),
        )

        assertThat(shows.orderedBy(SeriesSortOrder.RecentlyUpdated).map { it.name })
            .containsExactly("fresh", "stale", "never").inOrder()
    }

    @Test
    fun `channels sort both ways by name and otherwise as the provider listed them`() {
        val channels = listOf(
            channel("DE | ZDF HD", order = 0),
            channel("DE | ARD HD", order = 1),
        )

        assertThat(channels.orderedBy(LiveSortOrder.NameAscending).map { it.name })
            .containsExactly("DE | ARD HD", "DE | ZDF HD").inOrder()
        assertThat(channels.orderedBy(LiveSortOrder.NameDescending).map { it.name })
            .containsExactly("DE | ZDF HD", "DE | ARD HD").inOrder()
        assertThat(channels.orderedBy(LiveSortOrder.ProviderDefault).map { it.name })
            .containsExactly("DE | ZDF HD", "DE | ARD HD").inOrder()
    }

    @Test
    fun `a film sorts under its title rather than under the provider's language tag`() {
        val films = listOf(
            movie("DE | Zorro", order = 0),
            movie("EN - Avatar", order = 1),
        )

        // Under the raw name these come out in the listed order, because D precedes E. What the
        // phone does — and now this — is drop the tag and sort Avatar before Zorro.
        assertThat(films.orderedBy(MovieSortOrder.NameAscending).map { it.name })
            .containsExactly("EN - Avatar", "DE | Zorro").inOrder()
    }

    @Test
    fun `punctuation in front of a title does not push it past Z`() {
        val films = listOf(
            movie("Zulu", order = 0),
            movie("(2001) Amelie", order = 1),
            movie("\"Crocodile\" Dundee", order = 2),
        )

        assertThat(films.orderedBy(MovieSortOrder.NameAscending).map { it.name })
            .containsExactly("(2001) Amelie", "\"Crocodile\" Dundee", "Zulu").inOrder()
    }

    @Test
    fun `a series drops its language tag the same way a film does`() {
        val shows = listOf(
            series("DE | Tatort", order = 0),
            series("FR : Engrenages", order = 1),
        )

        assertThat(shows.orderedBy(SeriesSortOrder.NameAscending).map { it.name })
            .containsExactly("FR : Engrenages", "DE | Tatort").inOrder()
    }

    @Test
    fun `a channel keeps its language tag, because that is part of which channel it is`() {
        // The opposite decision from a film's, and the phone's as well: `DE |` in front of a channel
        // distinguishes it from the same channel in another language rather than hiding its name.
        val channels = listOf(
            channel("EN | Eurosport", order = 0),
            channel("DE | Eurosport", order = 1),
        )

        assertThat(channels.orderedBy(LiveSortOrder.NameAscending).map { it.name })
            .containsExactly("DE | Eurosport", "EN | Eurosport").inOrder()
    }

    @Test
    fun `equal names keep the provider's order in both directions`() {
        val channels = listOf(
            channel("Sport", order = 5),
            channel("Sport", order = 1),
        )

        // A tie is not part of what "Z to A" reverses.
        assertThat(channels.orderedBy(LiveSortOrder.NameAscending).map { it.providerOrder })
            .containsExactly(1, 5).inOrder()
        assertThat(channels.orderedBy(LiveSortOrder.NameDescending).map { it.providerOrder })
            .containsExactly(1, 5).inOrder()
    }

    private fun movie(
        name: String,
        order: Int,
        rating: Double? = null,
        year: Int? = null,
        added: Long? = null,
    ) = MovieSummary(
        id = name,
        categoryId = "1",
        name = name,
        posterUrl = null,
        containerExtension = "mkv",
        rating = rating,
        releaseYear = year,
        addedAtEpochSeconds = added,
        providerOrder = order,
    )

    private fun series(name: String, order: Int, modified: Long? = null) = SeriesSummary(
        id = name,
        categoryId = "1",
        name = name,
        posterUrl = null,
        rating = null,
        releaseYear = null,
        lastModifiedEpochSeconds = modified,
        providerOrder = order,
    )

    private fun channel(name: String, order: Int) = LiveChannel(
        id = name,
        categoryId = "1",
        name = name,
        logoUrl = null,
        epgChannelId = null,
        containerExtension = null,
        directSource = null,
        providerOrder = order,
    )
}
