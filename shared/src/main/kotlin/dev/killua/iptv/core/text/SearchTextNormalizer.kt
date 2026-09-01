package dev.killua.iptv.core.text

/**
 * The one normalization behind every stored sort key and every typed search term.
 *
 * Both sides have to agree exactly. A stored key of `mr. robot` cannot be found by a
 * `LIKE '%mr robot%'`, and the viewer typing the title of a show is not going to reproduce the
 * provider's punctuation. Folding both sides through here is what makes `Mr. Robot` findable as
 * `mr robot`, `mr. robot`, and `Mr Robot` alike.
 *
 * Two rules, and the difference between them matters:
 *
 * - an apostrophe is **removed**, because it never stands between two words: `Marvel's` and
 *   `Marvels` are the same thing to someone typing it;
 * - every other non-alphanumeric character becomes a **space**, because something readable usually
 *   stands on each side of it. Folding `Spider-Man` to `spiderman` would stop `spider man` from
 *   finding it, which trades one miss for another.
 *
 * Acronyms are the acknowledged cost: `S.W.A.T.` normalizes to `s w a t`, so typing `swat` still
 * finds nothing. That was already true before this rule existed, so nothing regressed; typing the
 * dots, or any part of the spaced form, works.
 *
 * Ordering shifts slightly as a side effect, and deliberately so: a title starting with a bracket
 * or a quotation mark now sorts under its first letter instead of after Z.
 */
object SearchTextNormalizer {
    /** Marks that join rather than separate, so they leave no gap behind. */
    private const val JOINING_MARKS = "'’ʼ`´"

    private val WHITESPACE = Regex("""\s+""")

    /** Lowercase, punctuation-folded, single-spaced, trimmed. Idempotent. */
    fun normalize(text: String): String {
        val folded = buildString(text.length) {
            text.forEach { character ->
                when {
                    character in JOINING_MARKS -> Unit
                    character.isLetterOrDigit() || character.isWhitespace() -> append(character)
                    else -> append(' ')
                }
            }
        }
        // Locale-independent by design: the same title must produce the same key on every device.
        return folded.lowercase().replace(WHITESPACE, " ").trim()
    }
}
