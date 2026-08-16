package dev.killua.iptv.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.killua.iptv.domain.model.ThemeMode
import dev.killua.iptv.domain.model.VideoScaleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException

class AppPreferences(private val dataStore: DataStore<Preferences>) {
    private object Keys {
        val theme = stringPreferencesKey("appearance.theme")
        val pictureInPicture = booleanPreferencesKey("playback.picture_in_picture")
        val doubleTapSeekSeconds = intPreferencesKey("playback.gestures.double_tap_seek_seconds")
        val holdSpeedHundredths = intPreferencesKey("playback.gestures.hold_speed_hundredths")
        val autoPlayNextEpisode = booleanPreferencesKey("playback.auto_play_next_episode")
        val videoScaleMode = stringPreferencesKey("playback.video_scale_mode")
        val playerBrightnessHundredths = intPreferencesKey("playback.player_brightness_hundredths")
        val introductionSeen = booleanPreferencesKey("onboarding.introduction_seen")
    }

    val themeMode: Flow<ThemeMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[Keys.theme]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.Dark
        }

    val pictureInPictureEnabled: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[Keys.pictureInPicture] ?: true }

    val doubleTapSeekSeconds: Flow<Int> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            PlaybackGestureOptions.validSeekSeconds(
                preferences[Keys.doubleTapSeekSeconds],
            )
        }

    val holdPlaybackSpeed: Flow<Float> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            PlaybackGestureOptions.validHoldSpeedHundredths(
                preferences[Keys.holdSpeedHundredths],
            ) / 100f
        }

    /** On by default: a series is the one place where continuing is the expected behaviour. */
    val autoPlayNextEpisode: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[Keys.autoPlayNextEpisode] ?: true }

    /**
     * How the picture fills the player, remembered across sessions.
     *
     * Stored by name rather than by ordinal so reordering the enum cannot silently change what a
     * saved preference means. An unrecognised value falls back to Fit, which is the honest one.
     */
    val videoScaleMode: Flow<VideoScaleMode> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[Keys.videoScaleMode]
                ?.let { runCatching { VideoScaleMode.valueOf(it) }.getOrNull() }
                ?: VideoScaleMode.Fit
        }

    /**
     * The player's own brightness, remembered across sessions, or null to follow the device.
     *
     * Null is a real state, not zero: someone who has never touched the slider should get the
     * screen brightness they set system-wide, not a value this app invented. Stored in hundredths
     * because DataStore preferences have no float-with-absent distinction worth the ambiguity.
     */
    val playerBrightness: Flow<Float?> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            preferences[Keys.playerBrightnessHundredths]
                ?.takeIf { it in 0..100 }
                ?.let { it / 100f }
        }

    /**
     * Whether the one-time introduction has been shown.
     *
     * Off by default, so an existing installation that updates into this build sees it once too —
     * the tour is about parts of the app that were never explained anywhere, not only about being
     * new to it.
     */
    val introductionSeen: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences -> preferences[Keys.introductionSeen] ?: false }

    suspend fun setIntroductionSeen(seen: Boolean) {
        dataStore.edit { it[Keys.introductionSeen] = seen }
    }

    suspend fun setPlayerBrightness(level: Float) {
        val hundredths = (level.coerceIn(0f, 1f) * 100f).toInt()
        dataStore.edit { it[Keys.playerBrightnessHundredths] = hundredths }
    }

    suspend fun setVideoScaleMode(mode: VideoScaleMode) {
        dataStore.edit { it[Keys.videoScaleMode] = mode.name }
    }

    suspend fun setAutoPlayNextEpisode(enabled: Boolean) {
        dataStore.edit { it[Keys.autoPlayNextEpisode] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.theme] = mode.name }
    }

    suspend fun setPictureInPictureEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.pictureInPicture] = enabled }
    }

    suspend fun setDoubleTapSeekSeconds(seconds: Int) {
        require(seconds in PlaybackGestureOptions.seekSeconds) {
            "Unsupported double-tap seek interval: $seconds seconds"
        }
        dataStore.edit { it[Keys.doubleTapSeekSeconds] = seconds }
    }

    suspend fun setHoldPlaybackSpeed(speed: Float) {
        val hundredths = (speed * 100).toInt()
        require(hundredths in PlaybackGestureOptions.holdSpeedHundredths) {
            "Unsupported hold playback speed: $speed"
        }
        dataStore.edit { it[Keys.holdSpeedHundredths] = hundredths }
    }
}

object PlaybackGestureOptions {
    val seekSeconds: List<Int> = listOf(5, 10, 15, 20, 30, 45, 60)
    val holdSpeeds: List<Float> = listOf(1.25f, 1.5f, 1.75f, 2f)

    const val defaultSeekSeconds = 10
    const val defaultHoldSpeedHundredths = 200

    internal val holdSpeedHundredths: Set<Int> = holdSpeeds
        .mapTo(linkedSetOf()) { (it * 100).toInt() }

    fun validSeekSeconds(storedValue: Int?): Int =
        storedValue?.takeIf(seekSeconds::contains) ?: defaultSeekSeconds

    fun validHoldSpeedHundredths(storedValue: Int?): Int =
        storedValue?.takeIf(holdSpeedHundredths::contains) ?: defaultHoldSpeedHundredths
}

fun Context.noBackupPreferencesFile(): File =
    File(noBackupFilesDir, "datastore/killuas_iptv.preferences_pb").also { file ->
        file.parentFile?.mkdirs()
    }
