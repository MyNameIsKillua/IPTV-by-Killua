package dev.killua.iptv.data.xtream

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Defensive parsing of the Series endpoints. Every fixture is fabricated; no provider response is
 * ever committed.
 */
class XtreamSeriesParserTest {
    private val parser = XtreamJsonParser()

    @Test
    fun `categories keep provider order and drop entries without an id`() {
        val payload = """
            [
              {"category_id":"90","category_name":"Serien"},
              {"category_name":"Ohne ID"},
              {"category_id":"91","category_name":"Doku"}
            ]
        """.trimIndent()

        val categories = parser.parseSeriesCategories(payload)

        assertThat(categories.map { it.id }).containsExactly("90", "91").inOrder()
        // The order counts positions in the provider's own list, so a skipped entry leaves a gap.
        // That is harmless: the value is only ever sorted on, exactly as for Live and Movies.
        assertThat(categories.map { it.sortOrder }).isInOrder()
    }

    @Test
    fun `a listing is de-duplicated by provider id and tolerates missing fields`() {
        val payload = """
            [
              {"num":1,"series_id":"7","name":"Beispielserie","cover":"https://images.provider.example/s7.jpg",
               "rating":"8.1","releaseDate":"2019-05-24","last_modified":"1690000000","category_id":"90"},
              {"series_id":"7","name":"Duplikat"},
              {"series_id":"8"},
              {"name":"Ohne ID"}
            ]
        """.trimIndent()

        val series = parser.parseSeriesSummaries(payload)

        assertThat(series.map { it.id }).containsExactly("7", "8").inOrder()
        val first = series.first()
        assertThat(first.name).isEqualTo("Beispielserie")
        assertThat(first.rating).isEqualTo(8.1)
        assertThat(first.releaseYear).isEqualTo(2019)
        assertThat(first.lastModifiedEpochSeconds).isEqualTo(1_690_000_000L)
        assertThat(first.categoryId).isEqualTo("90")
        // A listing entry with nothing but an ID still has to be usable.
        assertThat(series[1].name).isEqualTo("Series 8")
        assertThat(series[1].posterUrl).isNull()
    }

    @Test
    fun `a listing streams without being held whole`() = runTest {
        val payload = """
            [
              {"series_id":"7","name":"Erste"},
              {"series_id":"8","name":"Zweite"}
            ]
        """.trimIndent()

        val names = parser.withSeriesSummaries(
            ByteArrayInputStream(payload.toByteArray()),
        ) { sequence -> sequence.map { it.name }.toList() }

        assertThat(names).containsExactly("Erste", "Zweite").inOrder()
    }

    @Test
    fun `a keyed-object listing still parses through the buffered fallback`() = runTest {
        val payload = """
            {"7":{"series_id":"7","name":"Erste"},"8":{"series_id":"8","name":"Zweite"}}
        """.trimIndent()

        val ids = parser.withSeriesSummaries(
            ByteArrayInputStream(payload.toByteArray()),
        ) { sequence -> sequence.map { it.id }.toList() }

        assertThat(ids).containsExactly("7", "8")
    }

    @Test
    fun `details flatten the season map into ordered episodes`() {
        val details = parser.parseSeriesDetails(DETAILS_JSON, requestedId = "7")

        assertThat(details.name).isEqualTo("Beispielserie")
        assertThat(details.genre).isEqualTo("Drama")
        assertThat(details.releaseYear).isEqualTo(2019)
        assertThat(details.episodes.map { it.id }).containsExactly("101", "102", "201").inOrder()
        assertThat(details.episodes.map { it.seasonNumber }).containsExactly(1, 1, 2).inOrder()
        assertThat(details.episodes.map { it.episodeNumber }).containsExactly(1, 2, 1).inOrder()
    }

    @Test
    fun `an episode keeps its provider id, container, duration, and plot`() {
        val episode = parser.parseSeriesDetails(DETAILS_JSON, requestedId = "7").episodes.first()

        assertThat(episode.id).isEqualTo("101")
        assertThat(episode.title).isEqualTo("Der Anfang")
        assertThat(episode.containerExtension).isEqualTo("mkv")
        assertThat(episode.durationSeconds).isEqualTo(2_700)
        assertThat(episode.plot).isEqualTo("Eine synthetische Beschreibung.")
    }

    @Test
    fun `an episode without a provider id is skipped rather than given one`() {
        val payload = """
            {"info":{"name":"Beispielserie"},
             "episodes":{"1":[{"episode_num":1,"title":"Ohne ID"},{"id":"101","episode_num":2}]}}
        """.trimIndent()

        val episodes = parser.parseSeriesDetails(payload, requestedId = "7").episodes

        // Identity is what playback and progress key on; a synthesised one would mix up episodes.
        assertThat(episodes.map { it.id }).containsExactly("101")
    }

    @Test
    fun `an unrecognized container is dropped instead of reaching a decoder`() {
        val payload = """
            {"episodes":{"1":[{"id":"101","container_extension":"exe"}]}}
        """.trimIndent()

        val episode = parser.parseSeriesDetails(payload, requestedId = "7").episodes.single()

        assertThat(episode.containerExtension).isNull()
    }

    @Test
    fun `the season on an episode wins over the key it was filed under`() {
        val payload = """
            {"episodes":{"1":[{"id":"101","season":3,"episode_num":1}]}}
        """.trimIndent()

        val episode = parser.parseSeriesDetails(payload, requestedId = "7").episodes.single()

        assertThat(episode.seasonNumber).isEqualTo(3)
    }

    @Test
    fun `an episode list sent as an array is accepted`() {
        val payload = """
            {"episodes":[{"id":"101","season":1,"episode_num":1}]}
        """.trimIndent()

        val episodes = parser.parseSeriesDetails(payload, requestedId = "7").episodes

        assertThat(episodes.map { it.id }).containsExactly("101")
    }

    @Test
    fun `a payload describing another series is rejected`() {
        val payload = """{"info":{"series_id":"99","name":"Andere Serie"},"episodes":{}}"""

        val failure = runCatching { parser.parseSeriesDetails(payload, requestedId = "7") }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(AppFailureException::class.java)
        assertThat((failure as AppFailureException).failure.kind)
            .isEqualTo(FailureKind.InvalidServerResponse)
    }

    @Test
    fun `a payload with neither info nor episodes is rejected`() {
        listOf("""{"seasons":[]}""", "<html>error</html>", "", "   ").forEach { payload ->
            val failure = runCatching { parser.parseSeriesDetails(payload, requestedId = "7") }
                .exceptionOrNull()
            assertThat(failure).isInstanceOf(AppFailureException::class.java)
        }
    }

    @Test
    fun `a series with no episodes yet still yields a usable record`() {
        val payload = """{"info":{"name":"Angekuendigt"},"episodes":{}}"""

        val details = parser.parseSeriesDetails(payload, requestedId = "7")

        assertThat(details.name).isEqualTo("Angekuendigt")
        assertThat(details.episodes).isEmpty()
    }

    private companion object {
        val DETAILS_JSON = """
            {
              "info": {
                "series_id": "7",
                "name": "Beispielserie",
                "cover": "https://images.provider.example/s7.jpg",
                "plot": "Eine synthetische Serienbeschreibung.",
                "genre": "Drama",
                "cast": "Erika Mustermann",
                "director": "Max Mustermann",
                "releaseDate": "2019-05-24",
                "rating": "8.1",
                "backdrop_path": ["https://images.provider.example/b7.jpg"]
              },
              "episodes": {
                "2": [
                  {"id":"201","episode_num":1,"title":"Neue Wege","container_extension":"mp4",
                   "info":{"duration_secs":2400}}
                ],
                "1": [
                  {"id":"102","episode_num":2,"title":"Der Weg","container_extension":"mkv"},
                  {"id":"101","episode_num":1,"title":"Der Anfang","container_extension":"mkv",
                   "info":{"duration_secs":2700,"plot":"Eine synthetische Beschreibung.",
                           "movie_image":"https://images.provider.example/e101.jpg"}}
                ]
              }
            }
        """.trimIndent()
    }
}
