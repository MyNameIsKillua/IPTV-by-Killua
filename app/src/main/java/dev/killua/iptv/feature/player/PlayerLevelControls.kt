package dev.killua.iptv.feature.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager

/**
 * Screen brightness and media volume for the player route.
 *
 * Brightness is applied as a **window-local override** and never as the system setting. Writing
 * `Settings.System` would need a special permission and, worse, would change the device's own
 * brightness for every other app; a video player dimming itself must not dim the phone. The
 * override is handed back in [release], so nothing outside the player inherits it.
 *
 * Volume is the ordinary music stream, changed through `AudioManager` without the system's own
 * slider, because the player draws its own cue and two indicators would fight. Nothing here holds a
 * cached volume: every drag reads the current value first, so the hardware keys stay authoritative
 * between gestures.
 */
internal class PlayerLevelControls(
    private val window: Window?,
    private val audioManager: AudioManager?,
    private val systemBrightness: () -> Float,
) {
    /**
     * The window's own override once one has been applied, otherwise the device's current setting,
     * so the first drag of a session continues from what the viewer is actually looking at.
     */
    fun brightness(): Float {
        val override = window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        return if (override >= 0f) override.coerceIn(0f, 1f) else systemBrightness()
    }

    fun setBrightness(level: Float) {
        val window = window ?: return
        val attributes = window.attributes
        // Never quite black: a screen the viewer cannot see is one they cannot drag back up.
        attributes.screenBrightness = level.coerceIn(MINIMUM_BRIGHTNESS, 1f)
        window.attributes = attributes
    }

    /** Returns brightness to the system. Without this, every later screen keeps the player's. */
    fun release() {
        val window = window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attributes
    }

    fun volume(): Float {
        val manager = audioManager ?: return 0f
        return streamVolumeLevelOf(
            manager.getStreamVolume(AudioManager.STREAM_MUSIC),
            manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
    }

    fun setVolume(level: Float) {
        val manager = audioManager ?: return
        val maxIndex = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        manager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            streamVolumeIndexFor(level, maxIndex),
            0,
        )
    }

    companion object {
        /** Low enough to be a real dim, high enough that the picture is still findable. */
        const val MINIMUM_BRIGHTNESS = 0.02f
    }
}

/** Builds the controls for the Activity hosting [context]; missing pieces degrade to no-ops. */
internal fun playerLevelControls(context: Context): PlayerLevelControls = PlayerLevelControls(
    window = context.findActivity()?.window,
    audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager,
    systemBrightness = { systemBrightnessLevel(context) },
)

/**
 * A read-only look at the device's brightness setting, used as the starting point of the first
 * drag. Adaptive brightness can make it an approximation; being roughly right beats starting every
 * session in the middle of the range.
 */
private fun systemBrightnessLevel(context: Context): Float {
    val raw = runCatching {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            -1,
        )
    }.getOrDefault(-1)
    if (raw < 0) return DEFAULT_BRIGHTNESS
    return (raw / SYSTEM_BRIGHTNESS_MAX).coerceIn(0f, 1f)
}

private const val DEFAULT_BRIGHTNESS = 0.5f

/**
 * The conventional maximum of `Settings.System.SCREEN_BRIGHTNESS`. Android does not guarantee it,
 * so it only places the *starting* point of a drag; every value the player applies afterwards is
 * the window override, which is a true 0..1 scale.
 */
private const val SYSTEM_BRIGHTNESS_MAX = 255f
