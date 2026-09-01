package dev.killua.iptv.domain.model

import java.util.Locale

/**
 * The audio and subtitle languages the viewer has settled on, remembered across sessions.
 *
 * A provider hands the same series out with four audio tracks and no consistent order, so the track
 * the player picks on its own is whatever the file happens to list first. Choosing again on every
 * episode is the friction this removes.
 *
 * Null means *no preference*, which is a different state from a chosen language: it lets the player
 * decide, which is the right behaviour for someone who has never opened the track menu.
 * [subtitlesDisabled] is likewise its own state — subtitles switched off is a decision, and
 * expressing it as "no subtitle language" would silently let the next stream turn them back on.
 */
data class TrackLanguagePreferences(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesDisabled: Boolean = false,
) {
    val isEmpty: Boolean
        get() = audioLanguage == null && subtitleLanguage == null && !subtitlesDisabled
}

/**
 * What the viewer picked **by hand** in the player's track menu.
 *
 * Only a deliberate choice belongs in here. What the player selected on its own must never reach
 * it: a film that carries nothing but French audio would otherwise make French the preference for
 * everything watched afterwards, without anyone having asked for it.
 */
data class TrackLanguageSelection(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesTurnedOff: Boolean = false,
) {
    val isEmpty: Boolean
        get() = audioLanguage == null && subtitleLanguage == null && !subtitlesTurnedOff
}

/**
 * Folds a hand-made [selection] into the stored preferences, or returns null when nothing changed.
 *
 * Null is what keeps the writes bounded: this runs on the same rhythm as a watch-progress
 * checkpoint, and a viewer who never opens the track menu must not cause a store write every ten
 * seconds for the length of a film.
 *
 * A track type the selection says nothing about keeps whatever was stored. Turning subtitles off
 * clears the remembered subtitle language rather than keeping it beside the off state, because the
 * next explicit pick is what should define it.
 */
fun TrackLanguagePreferences.learnFrom(
    selection: TrackLanguageSelection,
): TrackLanguagePreferences? {
    val pickedAudio = normalizeLanguageTag(selection.audioLanguage)
    val pickedSubtitle = normalizeLanguageTag(selection.subtitleLanguage)
    val learned = TrackLanguagePreferences(
        audioLanguage = pickedAudio ?: audioLanguage,
        subtitleLanguage = when {
            pickedSubtitle != null -> pickedSubtitle
            selection.subtitlesTurnedOff -> null
            else -> subtitleLanguage
        },
        subtitlesDisabled = when {
            pickedSubtitle != null -> false
            selection.subtitlesTurnedOff -> true
            else -> subtitlesDisabled
        },
    )
    return learned.takeIf { it != this }
}

/**
 * Reduces a container's language field to a comparable tag, or null when it says nothing.
 *
 * Providers write the same language as `ger`, `de`, `de-DE`, or nothing at all. The undetermined
 * codes are dropped rather than stored: remembering "und" would mean preferring whichever track a
 * muxer forgot to label, on every title from then on.
 */
fun normalizeLanguageTag(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (trimmed.isEmpty()) return null
    return trimmed.takeUnless { it in UNDETERMINED_LANGUAGE_TAGS }
}

/**
 * A readable name for a stored tag, for the Settings rows that show what is remembered.
 *
 * Falls back to the tag itself when the platform does not recognise it, which is honest: a provider
 * is free to invent one, and showing it unchanged is better than showing nothing.
 */
fun languageDisplayName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    val name = locale.getDisplayLanguage(Locale.ENGLISH)
    return name.takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
        ?.replaceFirstChar { it.uppercase(Locale.ENGLISH) }
        ?: tag.uppercase(Locale.ENGLISH)
}

/** One selectable track, reduced to the only two things a language rule needs. */
data class TrackLanguage(val id: Int, val language: String?)

/**
 * Whether two container language fields mean the same language.
 *
 * Not string equality, because ISO 639 has **two** three-letter codes for a dozen major languages —
 * German is `ger` bibliographically and `deu` terminologically — and providers use whichever their
 * muxer wrote. A viewer who picked `ger` on one film and is handed `deu` on the next has, as far as
 * anyone but a computer is concerned, already chosen.
 *
 * Region is ignored: someone who chose `de-DE` means German, and refusing `de-AT` on that basis
 * would be a preference nobody asked for.
 */
fun languagesMatch(one: String?, other: String?): Boolean {
    val a = languageKey(one) ?: return false
    val b = languageKey(other) ?: return false
    return a == b
}

/**
 * The track to select for a remembered language, or null to leave the player's own choice alone.
 *
 * Null when nothing matches is the important half: a film that simply does not carry the language
 * someone prefers should play in whatever it has, not in silence or in the first track a rule
 * happened to reach for.
 */
fun chooseTrackFor(preferred: String?, tracks: List<TrackLanguage>): Int? {
    if (preferred == null) return null
    return tracks.firstOrNull { languagesMatch(it.language, preferred) }?.id
}

/**
 * A comparable key for a language field: the primary subtag, folded onto one of the two ISO codes.
 *
 * Two-letter tags are expanded rather than three-letter ones being shortened, because the expansion
 * is what the platform can do reliably; the pairs below are the ones it cannot.
 */
private fun languageKey(raw: String?): String? {
    val normalized = normalizeLanguageTag(raw) ?: return null
    val primary = normalized.substringBefore('-').substringBefore('_')
    val expanded = if (primary.length == 2) {
        Locale.forLanguageTag(primary).isO3Language.takeIf { it.isNotEmpty() } ?: primary
    } else {
        primary
    }
    return BIBLIOGRAPHIC_TO_TERMINOLOGIC[expanded] ?: expanded
}

/**
 * ISO 639-2/B to 639-2/T, for the languages that have both.
 *
 * The list is closed — ISO does not add to it — so this is a table rather than a heuristic. Only the
 * direction B to T is needed: everything is folded onto T, so two spellings of German both become
 * `deu` and compare equal.
 */
private val BIBLIOGRAPHIC_TO_TERMINOLOGIC = mapOf(
    "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho",
    "cze" to "ces", "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu",
    "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
    "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
)

/** `und` and `zxx` are the container's way of saying it does not know, or that there is no speech. */
private val UNDETERMINED_LANGUAGE_TAGS = setOf("und", "zxx", "unknown", "null")
