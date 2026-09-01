package dev.killua.iptv.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The single rule both the stored sort keys and the typed search terms go through. All titles here
 * are fictitious or well-known names used only as shapes.
 */
class SearchTextNormalizerTest {
    @Test
    fun `the reported case works from every spelling`() {
        val stored = SearchTextNormalizer.normalize("Mr. Robot")
        listOf("mr robot", "mr. robot", "Mr Robot", "MR.  ROBOT").forEach { typed ->
            assertThat(stored).contains(SearchTextNormalizer.normalize(typed))
        }
        assertThat(stored).isEqualTo("mr robot")
    }

    @Test
    fun `separating punctuation becomes a space so both words survive`() {
        assertThat(SearchTextNormalizer.normalize("Spider-Man")).isEqualTo("spider man")
        assertThat(SearchTextNormalizer.normalize("Tom & Jerry")).isEqualTo("tom jerry")
        assertThat(SearchTextNormalizer.normalize("Akte X: Die Wahrheit"))
            .isEqualTo("akte x die wahrheit")
        assertThat(SearchTextNormalizer.normalize("DE | RTL HD")).isEqualTo("de rtl hd")
    }

    @Test
    fun `an apostrophe joins rather than separates`() {
        assertThat(SearchTextNormalizer.normalize("Marvel's Agents")).isEqualTo("marvels agents")
        assertThat(SearchTextNormalizer.normalize("Marvel’s Agents"))
            .isEqualTo("marvels agents")
    }

    @Test
    fun `letters and digits are kept, including outside ASCII`() {
        assertThat(SearchTextNormalizer.normalize("Öl über Istanbul")).isEqualTo("öl über istanbul")
        assertThat(SearchTextNormalizer.normalize("4K Beispielfilm")).isEqualTo("4k beispielfilm")
        assertThat(SearchTextNormalizer.normalize("Die Hard 2")).isEqualTo("die hard 2")
    }

    @Test
    fun `whitespace is collapsed and trimmed whatever produced it`() {
        assertThat(SearchTextNormalizer.normalize("  Der   Pate  ")).isEqualTo("der pate")
        assertThat(SearchTextNormalizer.normalize("A -- B")).isEqualTo("a b")
        assertThat(SearchTextNormalizer.normalize("[GER] Film")).isEqualTo("ger film")
    }

    @Test
    fun `text with nothing searchable in it normalizes to nothing`() {
        assertThat(SearchTextNormalizer.normalize("...")).isEmpty()
        assertThat(SearchTextNormalizer.normalize("   ")).isEmpty()
        assertThat(SearchTextNormalizer.normalize("")).isEmpty()
    }

    @Test
    fun `normalizing an already normalized key changes nothing`() {
        // The browsing path normalizes a term and then builds a pattern from it, which normalizes
        // again; a rule that were not idempotent would quietly corrupt the second pass.
        listOf("Mr. Robot", "DE | RTL HD", "Marvel's Agents", "  Der   Pate  ").forEach { title ->
            val once = SearchTextNormalizer.normalize(title)
            assertThat(SearchTextNormalizer.normalize(once)).isEqualTo(once)
        }
    }

    @Test
    fun `an acronym folds to spaced letters, which is the accepted cost`() {
        // Typing `swat` still finds nothing — but it did not before this rule either, so this is a
        // documented limit rather than a regression. Typing the dots works.
        assertThat(SearchTextNormalizer.normalize("S.W.A.T.")).isEqualTo("s w a t")
        assertThat(SearchTextNormalizer.normalize("s.w.a.t")).isEqualTo("s w a t")
    }
}
