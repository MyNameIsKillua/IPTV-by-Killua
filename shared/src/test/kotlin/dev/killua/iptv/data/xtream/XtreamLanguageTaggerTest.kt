package dev.killua.iptv.data.xtream

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Xtream API exposes no language field, so these rules are a documented heuristic over
 * provider naming conventions. All fixtures are fictitious.
 */
class XtreamLanguageTaggerTest {
    @Test
    fun `category language is recognized in the common provider layouts`() {
        mapOf(
            "DE | Action" to "de",
            "DE- Komoedie" to "de",
            "[GER] Drama" to "de",
            "VOD | DE | Doku" to "de",
            "EN - Drama" to "en",
            "TR | Dram" to "tr",
            "FR/Comedie" to "fr",
            "Filme (Deutsch)" to "de",
            "ENGLISH MOVIES" to "en",
        ).forEach { (name, expected) ->
            assertThat(XtreamLanguageTagger.languageOfCategory(name)).isEqualTo(expected)
        }
    }

    @Test
    fun `a category without a known tag stays unlabelled`() {
        listOf("Action", "Neuheiten 2026", "4K UHD", "", "   ", null).forEach { name ->
            assertThat(XtreamLanguageTagger.languageOfCategory(name)).isNull()
        }
    }

    @Test
    fun `title language is accepted only as an explicit leading tag`() {
        assertThat(XtreamLanguageTagger.languageOfTitle("DE | Beispielfilm")).isEqualTo("de")
        assertThat(XtreamLanguageTagger.languageOfTitle("[EN] Example Film")).isEqualTo("en")
        assertThat(XtreamLanguageTagger.languageOfTitle("GER - Beispielfilm")).isEqualTo("de")
    }

    @Test
    fun `a title that merely is a language word is not tagged`() {
        // The film "IT" must not be classified as Italian, and a bare word is not a tag.
        listOf("IT", "It", "Italian Job", "German", "  ", null).forEach { title ->
            assertThat(XtreamLanguageTagger.languageOfTitle(title)).isNull()
        }
    }

    @Test
    fun `a leading language tag is stripped so sorting uses the real title`() {
        assertThat(XtreamLanguageTagger.sortNameOf("DE | Avatar")).isEqualTo("avatar")
        assertThat(XtreamLanguageTagger.sortNameOf("[GER] Zurueck in die Zukunft"))
            .isEqualTo("zurueck in die zukunft")
        assertThat(XtreamLanguageTagger.sortNameOf("EN - The   Matrix")).isEqualTo("the matrix")
    }

    @Test
    fun `an untagged title keeps its own text as the sort key`() {
        assertThat(XtreamLanguageTagger.sortNameOf("Avatar")).isEqualTo("avatar")
        assertThat(XtreamLanguageTagger.sortNameOf("  Der   Pate  ")).isEqualTo("der pate")
        assertThat(XtreamLanguageTagger.sortNameOf("IT")).isEqualTo("it")
        // A non-language prefix must survive rather than being mistaken for a tag. The dash itself
        // folds to a space like any other punctuation, so the words are still both there.
        assertThat(XtreamLanguageTagger.sortNameOf("4K - Beispielfilm")).isEqualTo("4k beispielfilm")
    }

    @Test
    fun `punctuation is folded out of the sort key`() {
        // The case the user hit: typing `mr robot` had to find a title stored as `mr. robot`.
        assertThat(XtreamLanguageTagger.sortNameOf("Mr. Robot")).isEqualTo("mr robot")
        assertThat(XtreamLanguageTagger.sortNameOf("DE | Mr. Robot")).isEqualTo("mr robot")
        assertThat(XtreamLanguageTagger.sortNameOf("Spider-Man")).isEqualTo("spider man")
        // An apostrophe joins rather than separates, so it leaves no gap behind.
        assertThat(XtreamLanguageTagger.sortNameOf("Marvel's Agents")).isEqualTo("marvels agents")
    }

    @Test
    fun `unicode titles are preserved`() {
        assertThat(XtreamLanguageTagger.sortNameOf("DE | Öl über Istanbul"))
            .isEqualTo("öl über istanbul")
    }
}
