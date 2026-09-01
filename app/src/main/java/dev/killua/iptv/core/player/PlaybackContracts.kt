package dev.killua.iptv.core.player

import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.TrackLanguageSelection
import kotlinx.coroutines.flow.StateFlow

/**
 * The commands a playback screen issues, separated from the Media3 implementation.
 *
 * `MediaController` cannot exist on the JVM, so without this seam the checkpoint and resume rules
 * around it could only be checked by hand on a device.
 */
interface PlaybackCommands {
    suspend fun playLive(
        account: Account,
        channel: LiveChannel,
        requestedFormat: String? = null,
    ): String

    suspend fun playMovie(
        account: Account,
        movie: MovieSummary,
        startPositionMs: Long = 0L,
    ): String

    suspend fun playEpisode(
        account: Account,
        episode: SeriesEpisode,
        startPositionMs: Long = 0L,
    ): String

    fun retry()
    fun reconnect()
    fun togglePlayPause(): Boolean
    fun seekBy(deltaMs: Long): Boolean
    fun beginTemporarySpeed(speed: Float): Boolean
    fun endTemporarySpeed()
    fun stopAndClear()
}

/** The player state a screen observes, and the position a progress write is captured from. */
interface PlaybackStateSource {
    val snapshot: StateFlow<PlaybackSnapshot>

    /** Main thread only; see [PlayerConnection.capturePosition]. */
    fun capturePosition(): PlaybackPosition?

    /**
     * The audio and subtitle languages picked by hand in the track menu, if any.
     *
     * Main thread only, for the same reason as [capturePosition].
     */
    fun captureTrackLanguages(): TrackLanguageSelection?
}
