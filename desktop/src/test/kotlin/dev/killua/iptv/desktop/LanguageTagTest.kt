package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import org.junit.Test

/**
 * Which language the client thinks a title is in.
 *
 * The rule itself is `:shared`'s and tested there; what is tested here is the **order** the desktop
 * asks its two questions in, which is the part a client can get wrong on its own — and the phone
 * already got right.
 */
class LanguageTagTest {

    private val categories = mapOf(
        "10" to "DE | SPORT",
        "20" to "EN | MOVIES 4K",
        "30" to "Allerlei",
    )

    @Test
    fun `the category decides first`() {
        // A channel whose own name says nothing is still German if its shelf says so. Reading the
        // title alone would leave this one unlabelled and hidden by any language filter.
        val channel = channel("Sky Sport 1", category = "10")

        assertThat(channel.languageTag(categories)).isEqualTo("de")
    }

    @Test
    fun `a title in a foreign category keeps the category's language`() {
        // The phone's order, and this is where it shows: providers file by shelf, not by title, so
        // the shelf is the stronger signal when the two disagree.
        val film = movie("FR | Le Grand Bleu", category = "20")

        assertThat(film.languageTag(categories)).isEqualTo("en")
    }

    @Test
    fun `an unnamed category falls through to the title`() {
        val film = movie("DE | Der Astronaut (2026)", category = "30")

        assertThat(film.languageTag(categories)).isEqualTo("de")
    }

    @Test
    fun `a title with no category at all is read on its own`() {
        assertThat(movie("EN | The Office", category = null).languageTag(categories))
            .isEqualTo("en")
        assertThat(series("DE | Tatort", category = null).languageTag(categories)).isEqualTo("de")
    }

    @Test
    fun `nothing recognised is left unlabelled rather than guessed`() {
        // A filter never matches these. A heuristic that hides what it was unsure about is worse
        // than one that leaves it in.
        assertThat(movie("Der Astronaut", category = "30").languageTag(categories)).isNull()
        assertThat(movie("Der Astronaut", category = "nope").languageTag(categories)).isNull()
    }

    @Test
    fun `a stored id and an episode carry no category and are read on their own`() {
        val stored = BrowseItem.Indexed(
            contentType = MOVIE_CONTENT_TYPE,
            id = "1",
            label = "DE | Der Astronaut",
            artworkUrl = null,
            containerExtension = "mkv",
        )

        assertThat(stored.languageTag(categories)).isEqualTo("de")
    }

    private fun channel(name: String, category: String?) = BrowseItem.Channel(
        LiveChannel(
            id = "1",
            categoryId = category,
            name = name,
            logoUrl = null,
            epgChannelId = null,
            containerExtension = null,
            directSource = null,
            providerOrder = 0,
        ),
    )

    private fun movie(name: String, category: String?) = BrowseItem.Movie(
        MovieSummary(
            id = "1",
            categoryId = category,
            name = name,
            posterUrl = null,
            containerExtension = "mkv",
            rating = null,
            releaseYear = null,
            addedAtEpochSeconds = null,
            providerOrder = 0,
        ),
    )

    private fun series(name: String, category: String?) = BrowseItem.Series(
        SeriesSummary(
            id = "1",
            categoryId = category,
            name = name,
            posterUrl = null,
            rating = null,
            releaseYear = null,
            lastModifiedEpochSeconds = null,
            providerOrder = 0,
        ),
    )
}
