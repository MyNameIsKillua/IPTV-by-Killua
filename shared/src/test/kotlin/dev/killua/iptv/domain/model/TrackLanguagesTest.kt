package dev.killua.iptv.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackLanguagesTest {

    @Test
    fun `a hand-picked audio language is learned`() {
        val learned = TrackLanguagePreferences()
            .learnFrom(TrackLanguageSelection(audioLanguage = "de"))

        assertThat(learned).isEqualTo(TrackLanguagePreferences(audioLanguage = "de"))
    }

    @Test
    fun `an unchanged selection is not worth a write`() {
        val stored = TrackLanguagePreferences(audioLanguage = "de")

        assertThat(stored.learnFrom(TrackLanguageSelection(audioLanguage = "de"))).isNull()
    }

    @Test
    fun `a selection that says nothing is not worth a write`() {
        val stored = TrackLanguagePreferences(audioLanguage = "de", subtitleLanguage = "en")

        assertThat(stored.learnFrom(TrackLanguageSelection())).isNull()
    }

    /** The point of the whole slice: one track type must not clear the other. */
    @Test
    fun `picking a subtitle language keeps the remembered audio language`() {
        val stored = TrackLanguagePreferences(audioLanguage = "de")

        val learned = stored.learnFrom(TrackLanguageSelection(subtitleLanguage = "en"))

        assertThat(learned).isEqualTo(
            TrackLanguagePreferences(audioLanguage = "de", subtitleLanguage = "en"),
        )
    }

    @Test
    fun `turning subtitles off is remembered and clears the language`() {
        val stored = TrackLanguagePreferences(audioLanguage = "de", subtitleLanguage = "en")

        val learned = stored.learnFrom(TrackLanguageSelection(subtitlesTurnedOff = true))

        assertThat(learned).isEqualTo(
            TrackLanguagePreferences(audioLanguage = "de", subtitlesDisabled = true),
        )
    }

    @Test
    fun `picking a subtitle language turns subtitles back on`() {
        val stored = TrackLanguagePreferences(subtitlesDisabled = true)

        val learned = stored.learnFrom(TrackLanguageSelection(subtitleLanguage = "en"))

        assertThat(learned).isEqualTo(TrackLanguagePreferences(subtitleLanguage = "en"))
    }

    @Test
    fun `an undetermined language is never stored`() {
        val stored = TrackLanguagePreferences(audioLanguage = "de")

        assertThat(stored.learnFrom(TrackLanguageSelection(audioLanguage = "und"))).isNull()
        assertThat(TrackLanguagePreferences().learnFrom(TrackLanguageSelection(audioLanguage = "zxx")))
            .isNull()
    }

    @Test
    fun `language tags are folded so the same language compares equal`() {
        assertThat(normalizeLanguageTag(" DE ")).isEqualTo("de")
        assertThat(normalizeLanguageTag("de-DE")).isEqualTo("de-de")
        assertThat(normalizeLanguageTag("")).isNull()
        assertThat(normalizeLanguageTag(null)).isNull()
    }

    @Test
    fun `a tag with different case is not a change worth writing`() {
        val stored = TrackLanguagePreferences(audioLanguage = "de")

        assertThat(stored.learnFrom(TrackLanguageSelection(audioLanguage = "DE"))).isNull()
    }

    @Test
    fun `a stored tag is shown by name and an invented one is shown as it is`() {
        assertThat(languageDisplayName("de")).isEqualTo("German")
        assertThat(languageDisplayName("en")).isEqualTo("English")
        assertThat(languageDisplayName("qqq")).isEqualTo("QQQ")
    }

    @Test
    fun `emptiness distinguishes no preference from subtitles switched off`() {
        assertThat(TrackLanguagePreferences().isEmpty).isTrue()
        assertThat(TrackLanguagePreferences(subtitlesDisabled = true).isEmpty).isFalse()
        assertThat(TrackLanguageSelection().isEmpty).isTrue()
        assertThat(TrackLanguageSelection(subtitlesTurnedOff = true).isEmpty).isFalse()
    }

    @Test
    fun `the two ISO codes for one language are the same language`() {
        // German is "ger" bibliographically and "deu" terminologically, and a provider uses whichever
        // its muxer wrote. Someone who picked one has already chosen the other.
        assertThat(languagesMatch("ger", "deu")).isTrue()
        assertThat(languagesMatch("fre", "fra")).isTrue()
        assertThat(languagesMatch("dut", "nld")).isTrue()
    }

    @Test
    fun `a two-letter tag matches its three-letter spelling`() {
        assertThat(languagesMatch("de", "deu")).isTrue()
        assertThat(languagesMatch("de", "ger")).isTrue()
        assertThat(languagesMatch("en", "eng")).isTrue()
    }

    @Test
    fun `region is ignored`() {
        // Someone who chose de-DE means German; refusing de-AT on that basis is a preference nobody
        // asked for.
        assertThat(languagesMatch("de-DE", "de-AT")).isTrue()
        assertThat(languagesMatch("de_DE", "ger")).isTrue()
        assertThat(languagesMatch("EN-gb", "eng")).isTrue()
    }

    @Test
    fun `different languages do not match`() {
        assertThat(languagesMatch("ger", "eng")).isFalse()
        assertThat(languagesMatch("nld", "deu")).isFalse()
    }

    @Test
    fun `nothing matches nothing`() {
        // Two unlabelled tracks are not "the same language" — they are two tracks nobody labelled.
        assertThat(languagesMatch(null, null)).isFalse()
        assertThat(languagesMatch("und", "und")).isFalse()
        assertThat(languagesMatch("", "deu")).isFalse()
    }

    @Test
    fun `a remembered language picks the track that carries it`() {
        val tracks = listOf(
            TrackLanguage(id = 1, language = "eng"),
            TrackLanguage(id = 2, language = "ger"),
            TrackLanguage(id = 3, language = null),
        )

        assertThat(chooseTrackFor("deu", tracks)).isEqualTo(2)
        assertThat(chooseTrackFor("en", tracks)).isEqualTo(1)
    }

    @Test
    fun `nothing is chosen when nothing matches`() {
        val tracks = listOf(TrackLanguage(id = 1, language = "eng"))

        // A film that does not carry the preferred language should play in what it has, rather than
        // in silence or in whichever track a rule reached for first.
        assertThat(chooseTrackFor("deu", tracks)).isNull()
        assertThat(chooseTrackFor(null, tracks)).isNull()
        assertThat(chooseTrackFor("deu", emptyList())).isNull()
    }
}
