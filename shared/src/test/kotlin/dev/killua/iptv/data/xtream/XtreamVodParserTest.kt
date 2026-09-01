package dev.killua.iptv.data.xtream

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import org.junit.Test

/**
 * Every fixture in this suite is fictitious. Xtream VOD payloads differ widely between providers,
 * so these cases describe defensive parsing only; they cannot prove compatibility with any real
 * provider.
 */
class XtreamVodParserTest {
    private val parser = XtreamJsonParser()

    @Test
    fun `movie categories keep provider order and drop entries without a stable ID`() {
        val categories = parser.parseMovieCategories(
            """
            [
              {"category_id":"20","category_name":"Action"},
              {"category_name":"Missing ID"},
              {"category_id":"21","category_name":"Dokumentationen"},
              {"category_id":"20","category_name":"Duplicate"}
            ]
            """.trimIndent(),
        )

        assertThat(categories.map { it.id }).containsExactly("20", "21").inOrder()
        assertThat(categories.map { it.sortOrder }).containsExactly(0, 2).inOrder()
        assertThat(categories[1].name).isEqualTo("Dokumentationen")
    }

    @Test
    fun `movie categories accept a keyed object and supply a fallback name`() {
        val categories = parser.parseMovieCategories(
            """{"a":{"category_id":"9"},"b":{"category_id":"10","category_name":"  "}}""",
        )

        assertThat(categories.map { it.name }).containsExactly("Category 9", "Category 10")
    }

    @Test
    fun `movie summaries de-duplicate by stream ID and keep the first occurrence`() {
        val movies = parser.parseMovieSummaries(
            """
            [
              {"stream_id":"501","name":"Erster Film","num":11},
              {"stream_id":"501","name":"Duplicate"},
              {"name":"No stream id"},
              {"stream_id":"502","title":"Zweiter Film"}
            ]
            """.trimIndent(),
        )

        assertThat(movies.map { it.id }).containsExactly("501", "502").inOrder()
        assertThat(movies[0].name).isEqualTo("Erster Film")
        assertThat(movies[0].providerOrder).isEqualTo(11)
        assertThat(movies[1].name).isEqualTo("Zweiter Film")
        // No provider `num`, so the position in the response is used instead.
        assertThat(movies[1].providerOrder).isEqualTo(3)
    }

    @Test
    fun `movie summaries parse inconsistent numbers strings and placeholder values`() {
        val movies = parser.parseMovieSummaries(
            """
            [
              {
                "stream_id":505,
                "name":"Zahlen als Strings",
                "category_id":"20",
                "rating":"7.4",
                "year":"2019",
                "added":"1690000000",
                "container_extension":"MKV",
                "stream_icon":"https://images.provider.example/a.jpg"
              },
              {
                "stream_id":"506",
                "name":"Platzhalter",
                "category_id":"N/A",
                "rating":0,
                "year":"undefined",
                "container_extension":"exe",
                "stream_icon":"not a url"
              }
            ]
            """.trimIndent(),
        )

        val (first, second) = movies
        assertThat(first.id).isEqualTo("505")
        assertThat(first.rating).isWithin(1e-9).of(7.4)
        assertThat(first.releaseYear).isEqualTo(2019)
        assertThat(first.addedAtEpochSeconds).isEqualTo(1_690_000_000L)
        assertThat(first.containerExtension).isEqualTo("mkv")
        assertThat(first.posterUrl).isEqualTo("https://images.provider.example/a.jpg")

        assertThat(second.categoryId).isNull()
        assertThat(second.rating).isNull()
        assertThat(second.releaseYear).isNull()
        // An extension outside the safe list is dropped rather than carried into a media URL.
        assertThat(second.containerExtension).isNull()
        assertThat(second.posterUrl).isNull()
    }

    @Test
    fun `movie summaries fall back to a neutral name and cover artwork`() {
        val movie = parser.parseMovieSummaries(
            """[{"stream_id":"777","cover":"http://art.provider.example/p.png"}]""",
        ).single()

        assertThat(movie.name).isEqualTo("Movie 777")
        assertThat(movie.posterUrl).isEqualTo("http://art.provider.example/p.png")
    }

    @Test
    fun `movie details read nested info and movie_data sections`() {
        val details = parser.parseMovieDetails(
            """
            {
              "info": {
                "movie_image":"https://images.provider.example/poster.jpg",
                "backdrop_path":["https://images.provider.example/back.jpg"],
                "plot":"Eine kurze Beschreibung.",
                "genre":"Drama",
                "cast":"Erika Mustermann",
                "director":"Max Mustermann",
                "releasedate":"2019-05-24",
                "rating":"8.1",
                "duration_secs":6753
              },
              "movie_data": {
                "stream_id":"901",
                "name":"Beispielfilm",
                "category_id":"20",
                "container_extension":"mp4"
              }
            }
            """.trimIndent(),
            requestedId = "901",
        )

        assertThat(details.id).isEqualTo("901")
        assertThat(details.name).isEqualTo("Beispielfilm")
        assertThat(details.categoryId).isEqualTo("20")
        assertThat(details.containerExtension).isEqualTo("mp4")
        assertThat(details.posterUrl).isEqualTo("https://images.provider.example/poster.jpg")
        assertThat(details.backdropUrl).isEqualTo("https://images.provider.example/back.jpg")
        assertThat(details.plot).isEqualTo("Eine kurze Beschreibung.")
        assertThat(details.genre).isEqualTo("Drama")
        assertThat(details.cast).isEqualTo("Erika Mustermann")
        assertThat(details.director).isEqualTo("Max Mustermann")
        assertThat(details.releaseYear).isEqualTo(2019)
        assertThat(details.rating).isWithin(1e-9).of(8.1)
        assertThat(details.durationSeconds).isEqualTo(6753)
    }

    @Test
    fun `movie details accept alternate field names and a clock-style duration`() {
        val details = parser.parseMovieDetails(
            """
            {
              "info": {
                "title":"Alternative Felder",
                "cover_big":"https://images.provider.example/big.jpg",
                "backdrop_path":"https://images.provider.example/single.jpg",
                "description":"Beschreibung statt plot.",
                "actors":"Beispiel Darsteller",
                "release_date":"24-05-2001",
                "duration":"01:52:33"
              }
            }
            """.trimIndent(),
            requestedId = "902",
        )

        assertThat(details.id).isEqualTo("902")
        assertThat(details.name).isEqualTo("Alternative Felder")
        assertThat(details.posterUrl).isEqualTo("https://images.provider.example/big.jpg")
        assertThat(details.backdropUrl).isEqualTo("https://images.provider.example/single.jpg")
        assertThat(details.plot).isEqualTo("Beschreibung statt plot.")
        assertThat(details.cast).isEqualTo("Beispiel Darsteller")
        assertThat(details.releaseYear).isEqualTo(2001)
        assertThat(details.durationSeconds).isEqualTo(6753)
    }

    @Test
    fun `movie details tolerate missing descriptive fields`() {
        val details = parser.parseMovieDetails(
            """{"info":{},"movie_data":{"stream_id":"903"}}""",
            requestedId = "903",
        )

        assertThat(details.id).isEqualTo("903")
        assertThat(details.name).isEqualTo("Movie 903")
        assertThat(details.posterUrl).isNull()
        assertThat(details.backdropUrl).isNull()
        assertThat(details.plot).isNull()
        assertThat(details.rating).isNull()
        assertThat(details.durationSeconds).isNull()
        assertThat(details.containerExtension).isNull()
    }

    @Test
    fun `movie details reject a payload describing a different title`() {
        val failure = assertFailure {
            parser.parseMovieDetails(
                """{"movie_data":{"stream_id":"999","name":"Falscher Film"}}""",
                requestedId = "903",
            )
        }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.InvalidServerResponse)
    }

    @Test
    fun `movie details reject empty malformed and unusable payloads`() {
        listOf("[]", "{}", "", "<html>error</html>", "null", """{"info":null}""").forEach { payload ->
            val failure = assertFailure { parser.parseMovieDetails(payload, requestedId = "903") }
            assertThat(failure.failure.kind).isEqualTo(FailureKind.InvalidServerResponse)
        }
    }

    @Test
    fun `movie listings reject malformed payloads but tolerate an empty library`() {
        listOf("", "<html>error</html>", "\"text\"").forEach { payload ->
            assertThat(assertFailure { parser.parseMovieSummaries(payload) }.failure.kind)
                .isEqualTo(FailureKind.InvalidServerResponse)
        }
        assertThat(parser.parseMovieSummaries("[]")).isEmpty()
        assertThat(parser.parseMovieCategories("null")).isEmpty()
    }

    @Test
    fun `ratings are clamped to the ten point scale and durations must be positive`() {
        val movies = parser.parseMovieSummaries(
            """
            [
              {"stream_id":"1","rating":"12.5"},
              {"stream_id":"2","rating":"-3"},
              {"stream_id":"3","rating":"not a number"}
            ]
            """.trimIndent(),
        )

        assertThat(movies[0].rating).isWithin(1e-9).of(10.0)
        assertThat(movies[1].rating).isNull()
        assertThat(movies[2].rating).isNull()

        val zeroDuration = parser.parseMovieDetails(
            """{"info":{"duration":"00:00:00","duration_secs":0}}""",
            requestedId = "5",
        )
        assertThat(zeroDuration.durationSeconds).isNull()
    }

    @Test
    fun `implausible years are rejected`() {
        val movies = parser.parseMovieSummaries(
            """
            [
              {"stream_id":"1","year":"1650"},
              {"stream_id":"2","year":"12"},
              {"stream_id":"3","year":"2031"}
            ]
            """.trimIndent(),
        )

        assertThat(movies[0].releaseYear).isNull()
        assertThat(movies[1].releaseYear).isNull()
        assertThat(movies[2].releaseYear).isEqualTo(2031)
    }

    private fun assertFailure(block: () -> Unit): AppFailureException {
        try {
            block()
            throw AssertionError("Expected AppFailureException")
        } catch (failure: AppFailureException) {
            return failure
        }
    }
}
