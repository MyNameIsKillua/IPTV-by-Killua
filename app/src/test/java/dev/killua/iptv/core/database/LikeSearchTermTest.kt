package dev.killua.iptv.core.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The folding and escaping every search in the app depends on. All terms here are fictitious.
 */
class LikeSearchTermTest {
    @Test
    fun `a plain term becomes a lowercased contains pattern`() {
        assertThat(LikeSearchTerm.containsPattern("Tatort")).isEqualTo("%tatort%")
        assertThat(LikeSearchTerm.containsPattern("  Tatort  ")).isEqualTo("%tatort%")
    }

    @Test
    fun `a typed term is folded the same way the stored key was`() {
        // The reported bug: `mr robot` found nothing because the stored key reads `mr robot` while
        // the typed term still carried the provider's dot. All three spellings must agree now.
        assertThat(LikeSearchTerm.containsPattern("mr robot")).isEqualTo("%mr robot%")
        assertThat(LikeSearchTerm.containsPattern("mr. robot")).isEqualTo("%mr robot%")
        assertThat(LikeSearchTerm.containsPattern("Mr Robot")).isEqualTo("%mr robot%")
    }

    @Test
    fun `wildcards cannot reach the pattern, and are escaped if they ever do`() {
        // Folding removes them first, so a search for "100%" is a search for "100".
        assertThat(LikeSearchTerm.containsPattern("100%")).isEqualTo("%100%")
        assertThat(LikeSearchTerm.containsPattern("a_b")).isEqualTo("%a b%")
        assertThat(LikeSearchTerm.containsPattern("back\\slash")).isEqualTo("%back slash%")

        // The escape itself stays, because it is the last line of defence if folding ever changes.
        assertThat(LikeSearchTerm.escape("100%")).isEqualTo("100\\%")
        assertThat(LikeSearchTerm.escape("a_b")).isEqualTo("a\\_b")
        assertThat(LikeSearchTerm.escape("back\\slash")).isEqualTo("back\\\\slash")
    }

    @Test
    fun `a term shorter than the global minimum is refused`() {
        assertThat(LikeSearchTerm.globalContainsPattern(null)).isNull()
        assertThat(LikeSearchTerm.globalContainsPattern("")).isNull()
        assertThat(LikeSearchTerm.globalContainsPattern("a")).isNull()
        assertThat(LikeSearchTerm.globalContainsPattern("  a  ")).isNull()
    }

    @Test
    fun `a term at the global minimum is accepted`() {
        assertThat(LikeSearchTerm.MINIMUM_GLOBAL_LENGTH).isEqualTo(2)
        assertThat(LikeSearchTerm.globalContainsPattern("ab")).isEqualTo("%ab%")
    }

    @Test
    fun `a term of nothing but punctuation is refused rather than matching everything`() {
        // Without the folded length check these would each pass the raw length test and bind
        // "%%", which is a full scan of three six-figure tables returning the entire cache.
        assertThat(LikeSearchTerm.globalContainsPattern("%")).isNull()
        assertThat(LikeSearchTerm.globalContainsPattern("...")).isNull()
        assertThat(LikeSearchTerm.globalContainsPattern(" - ")).isNull()
        assertThat(LikeSearchTerm.normalize("...")).isEmpty()
    }
}
