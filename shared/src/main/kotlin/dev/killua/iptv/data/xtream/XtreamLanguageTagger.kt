package dev.killua.iptv.data.xtream

import dev.killua.iptv.core.text.SearchTextNormalizer

/**
 * Best-effort language detection for Xtream text, used for both Movie titles and channel names.
 *
 * The Xtream API has no language field. Providers conventionally encode it in category names and
 * sometimes in title prefixes, for example `DE | Action`, `[GER] Drama`, or `EN - Some Title`.
 * This object recognizes that convention and nothing more, so a result is always a heuristic and
 * never authoritative. Undetected text yields null rather than a guess.
 *
 * Categories and titles are treated differently on purpose. A category name may carry its tag
 * anywhere between delimiters, because `VOD | DE | Action` is common. A title is only accepted
 * when the tag stands at the very start and is followed by a delimiter and further text, so the
 * film titled `IT` is not mistaken for Italian while `IT | Some Title` still resolves. The same
 * rule keeps a channel called `IT` out of the Italian filter.
 */
object XtreamLanguageTagger {
    private val LANGUAGE_BY_TOKEN: Map<String, String> = buildMap {
        putTokens("de", "de", "ger", "deu", "german", "deutsch", "germany")
        putTokens("en", "en", "eng", "english", "usa", "uk")
        putTokens("fr", "fr", "fra", "fre", "french", "france")
        putTokens("es", "es", "spa", "esp", "spanish", "espanol")
        putTokens("it", "it", "ita", "italian", "italy")
        putTokens("tr", "tr", "tur", "turkish", "turkce")
        putTokens("ar", "ar", "ara", "arabic", "arabisch")
        putTokens("ru", "ru", "rus", "russian")
        putTokens("nl", "nl", "dut", "nld", "dutch")
        putTokens("pl", "pl", "pol", "polish")
        putTokens("pt", "pt", "por", "portuguese")
        putTokens("ro", "ro", "rom", "ron", "romanian")
        putTokens("sv", "sv", "swe", "swedish")
        putTokens("da", "da", "dan", "danish")
        putTokens("no", "no", "nor", "norwegian")
        putTokens("fi", "fi", "fin", "finnish")
        putTokens("cs", "cs", "cze", "czech")
        putTokens("el", "el", "gre", "greek")
        putTokens("hi", "hi", "hin", "hindi")
        putTokens("ja", "ja", "jpn", "japanese")
        putTokens("ko", "ko", "kor", "korean")
        putTokens("zh", "zh", "chi", "zho", "chinese")
    }

    private fun MutableMap<String, String>.putTokens(tag: String, vararg tokens: String) {
        tokens.forEach { put(it, tag) }
    }

    private val DELIMITERS = charArrayOf('|', '[', ']', '(', ')', '-', ':', '/', '\\', ',', '.', '_')
    private val TITLE_TAG = Regex("""^\s*[\[(|]?\s*([\p{L}]{2,8})\s*[\])|:\-–—/]+\s*(\S.*)$""")

    /** Language of a provider category name, or null when no known tag is present. */
    fun languageOfCategory(name: String?): String? {
        val text = name?.takeIf { it.isNotBlank() } ?: return null
        return text.split(*DELIMITERS, ' ')
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length in 2..8 }
            .firstNotNullOfOrNull { LANGUAGE_BY_TOKEN[it] }
    }

    /** Language of a Movie title or channel name, accepted only as an explicit leading tag. */
    fun languageOfTitle(name: String?): String? {
        val match = TITLE_TAG.find(name?.takeIf { it.isNotBlank() } ?: return null) ?: return null
        return LANGUAGE_BY_TOKEN[match.groupValues[1].lowercase()]
    }

    /**
     * Removes a recognized leading language tag so `DE | Avatar` sorts under A rather than D.
     * Returns the original text when no tag is recognized.
     */
    fun stripLanguagePrefix(name: String): String {
        val match = TITLE_TAG.find(name) ?: return name
        if (LANGUAGE_BY_TOKEN[match.groupValues[1].lowercase()] == null) return name
        return match.groupValues[2].trim().ifEmpty { name }
    }

    /**
     * Normalized key for alphabetical paging and title search: without a language prefix, then
     * folded by [SearchTextNormalizer] so ordering does not depend on collation at query time and
     * a search matches whether or not the viewer typed the provider's punctuation.
     */
    fun sortNameOf(name: String): String =
        SearchTextNormalizer.normalize(stripLanguagePrefix(name))
}
