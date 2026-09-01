package dev.killua.iptv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.TrackLanguageSelection

/**
 * Translates between the remembered languages and Media3's track selection.
 *
 * Everything here is Media3-shaped and therefore cannot run on the JVM, so it stays as thin as it
 * can be: the decisions live in `TrackLanguages.kt`, which is plain Kotlin and tested. This file
 * only reads and writes what the player understands.
 */

/**
 * Applies the remembered languages, clearing any override the previous title left behind.
 *
 * An override names a concrete track group of a concrete stream. Carrying one into the next title
 * would either match nothing or, worse, match a group at the same index that holds another
 * language. Preferences are the durable form of the same wish; overrides are not.
 */
fun TrackSelectionParameters.withLanguages(
    languages: TrackLanguagePreferences,
): TrackSelectionParameters = buildUpon()
    .clearOverrides()
    .setPreferredAudioLanguage(languages.audioLanguage)
    .setPreferredTextLanguage(languages.subtitleLanguage)
    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, languages.subtitlesDisabled)
    .build()

/** Sets [languages] on the player when it accepts track selection at all. */
fun Player.applyTrackLanguages(languages: TrackLanguagePreferences) {
    if (!isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) return
    trackSelectionParameters = trackSelectionParameters.withLanguages(languages)
}

/**
 * Reads back only what the viewer chose by hand.
 *
 * The overrides are the signal. Nothing in this app writes one — [withLanguages] clears them before
 * every title — so an override present afterwards can only have come from the stock track menu.
 * The automatic selection is deliberately not read: it reflects what the file offers, not what the
 * viewer wants.
 *
 * Read at a moment this app chooses rather than waiting for a change callback. A settled playback
 * speed was once lost exactly that way, because `MediaController` never reported it; see
 * `docs/PLAYER.md`.
 *
 * Reading a group's length, format, and type is unstable Media3 API. There is no stable way to ask
 * what language an overridden track is in, and the alternative — storing the override itself — is
 * exactly what must not be persisted, because it names a track group of one particular stream.
 */
@OptIn(UnstableApi::class)
fun Player.readHandPickedLanguages(): TrackLanguageSelection {
    val parameters = trackSelectionParameters
    var audioLanguage: String? = null
    var subtitleLanguage: String? = null
    for (override in parameters.overrides.values) {
        val trackIndex = override.trackIndices.firstOrNull() ?: continue
        val group = override.mediaTrackGroup
        if (trackIndex !in 0 until group.length) continue
        val language = group.getFormat(trackIndex).language
        when (group.type) {
            C.TRACK_TYPE_AUDIO -> audioLanguage = language
            C.TRACK_TYPE_TEXT -> subtitleLanguage = language
            else -> Unit
        }
    }
    return TrackLanguageSelection(
        audioLanguage = audioLanguage,
        subtitleLanguage = subtitleLanguage,
        subtitlesTurnedOff = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes,
    )
}
