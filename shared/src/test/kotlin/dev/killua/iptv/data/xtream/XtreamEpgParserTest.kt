package dev.killua.iptv.data.xtream

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.AppFailureException
import org.junit.Test
import java.util.Base64

/**
 * Defensive parsing of `get_short_epg`. Every fixture is fabricated; no provider data is used.
 */
class XtreamEpgParserTest {
    private val parser = XtreamJsonParser()

    @Test
    fun `base64 titles and descriptions are decoded`() {
        val payload = """
            {"epg_listings":[
              {"title":"${encode("Tagesschau")}","description":"${encode("Nachrichten um acht")}",
               "start_timestamp":"1700000000","stop_timestamp":"1700001800"}
            ]}
        """.trimIndent()

        val entries = parser.parseShortEpg(payload)

        assertThat(entries).hasSize(1)
        assertThat(entries.single().title).isEqualTo("Tagesschau")
        assertThat(entries.single().description).isEqualTo("Nachrichten um acht")
    }

    @Test
    fun `a plain-text title is left alone`() {
        // Not every provider encodes, and a four-letter ASCII word is accidentally valid Base64.
        val payload = """
            {"epg_listings":[
              {"title":"News","start_timestamp":"1700000000","stop_timestamp":"1700001800"}
            ]}
        """.trimIndent()

        val entries = parser.parseShortEpg(payload)

        assertThat(entries.single().title).isEqualTo("News")
    }

    @Test
    fun `a title with spaces or umlauts is never mistaken for base64`() {
        val payload = """
            {"epg_listings":[
              {"title":"Tatort: Der Fall Ö","start_timestamp":"1700000000",
               "stop_timestamp":"1700001800"}
            ]}
        """.trimIndent()

        assertThat(parser.parseShortEpg(payload).single().title).isEqualTo("Tatort: Der Fall Ö")
    }

    @Test
    fun `entries are sorted by start time regardless of the order they arrive in`() {
        val payload = """
            {"epg_listings":[
              {"title":"Zweitens","start_timestamp":"1700003600","stop_timestamp":"1700007200"},
              {"title":"Erstens","start_timestamp":"1700000000","stop_timestamp":"1700003600"}
            ]}
        """.trimIndent()

        assertThat(parser.parseShortEpg(payload).map { it.title })
            .containsExactly("Erstens", "Zweitens")
            .inOrder()
    }

    @Test
    fun `an entry without usable timestamps is dropped rather than guessed`() {
        // The formatted strings carry no offset, so placing this entry would need a guess.
        val payload = """
            {"epg_listings":[
              {"title":"Ohne Zeit","start":"2024-01-01 20:15:00","end":"2024-01-01 21:45:00"},
              {"title":"Mit Zeit","start_timestamp":"1700000000","stop_timestamp":"1700003600"}
            ]}
        """.trimIndent()

        assertThat(parser.parseShortEpg(payload).map { it.title }).containsExactly("Mit Zeit")
    }

    @Test
    fun `an entry that ends before it starts is dropped`() {
        val payload = """
            {"epg_listings":[
              {"title":"Verdreht","start_timestamp":"1700003600","stop_timestamp":"1700000000"}
            ]}
        """.trimIndent()

        assertThat(parser.parseShortEpg(payload)).isEmpty()
    }

    @Test
    fun `end_timestamp is accepted where a provider omits stop_timestamp`() {
        val payload = """
            {"epg_listings":[
              {"title":"Anders benannt","start_timestamp":"1700000000",
               "end_timestamp":"1700003600"}
            ]}
        """.trimIndent()

        assertThat(parser.parseShortEpg(payload).single().endEpochSeconds).isEqualTo(1_700_003_600L)
    }

    @Test
    fun `a bare array is accepted as well as the documented wrapper`() {
        val payload = """
            [{"title":"Direkt","start_timestamp":"1700000000","stop_timestamp":"1700003600"}]
        """.trimIndent()

        assertThat(parser.parseShortEpg(payload).map { it.title }).containsExactly("Direkt")
    }

    @Test
    fun `a channel with no guide yields an empty list rather than a failure`() {
        assertThat(parser.parseShortEpg("""{"epg_listings":[]}""")).isEmpty()
        assertThat(parser.parseShortEpg("""{"other":"shape"}""")).isEmpty()
    }

    @Test
    fun `an unparseable payload is a safe failure`() {
        val failure = runCatching { parser.parseShortEpg("not json at all") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AppFailureException::class.java)
    }

    private fun encode(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
}
