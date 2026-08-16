package dev.killua.iptv.core.database

import dev.killua.iptv.core.text.SearchTextNormalizer

/**
 * Prepares user-typed text for a SQL `LIKE` match against the pre-normalized `sortName` column.
 *
 * Shared by the paged browsing statements and the global search queries so a term is escaped and
 * folded exactly once, the same way, everywhere. Getting this wrong is not cosmetic: an unescaped
 * `%` turns a search into "match every row", which on a six-figure library means a full scan
 * returning the entire cache.
 *
 * The typed term goes through the same [SearchTextNormalizer] the stored keys do, which is what
 * makes `mr robot` find `Mr. Robot`. Folding first also means a term of nothing but punctuation
 * collapses to empty rather than to `%%`, so the callers below check for that.
 */
object LikeSearchTerm {
    /** SQLite has no default LIKE escape character, so every query must name one. */
    const val ESCAPE = '\\'

    /**
     * The shortest term global search will run.
     *
     * A single character matches too much to be worth scanning three six-figure tables for, and
     * the answer would be useless anyway. Browsing inside one library deliberately does **not**
     * apply this: there the term narrows a list the user is already looking at, and one character
     * is a reasonable thing to type.
     */
    const val MINIMUM_GLOBAL_LENGTH = 2

    /**
     * The searchable form of typed text: exactly what the stored `sortName` keys carry.
     *
     * Length checks belong on this, not on the raw input. A term of `...` is three characters the
     * user typed and nothing at all to match on.
     */
    fun normalize(term: String?): String = SearchTextNormalizer.normalize(term.orEmpty())

    /**
     * The bound pattern for a contains match.
     *
     * A term that normalizes to nothing would yield `%%` and match every row, so callers must
     * establish that something searchable is left first; both of the ones below do.
     */
    fun containsPattern(term: String): String = "%${escape(normalize(term))}%"

    /** Null when nothing searchable is left, or too little for a global search to be worth it. */
    fun globalContainsPattern(term: String?): String? {
        val normalized = normalize(term)
        return if (normalized.length >= MINIMUM_GLOBAL_LENGTH) containsPattern(normalized) else null
    }

    /** Escapes the LIKE wildcards so a term is matched literally. */
    fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            if (character == '%' || character == '_' || character == ESCAPE) {
                append(ESCAPE)
            }
            append(character)
        }
    }
}
