package dev.killua.iptv.core.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import dev.killua.iptv.data.xtream.XtreamStreamUrlFactory
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.model.displayLabel
import dev.killua.iptv.domain.repository.SessionRepository

class PlaybackCoordinator(
    private val connection: PlayerConnection,
    private val sessionRepository: SessionRepository,
    /** Read once per title, so a change made in Settings applies from the next one on. */
    private val trackLanguages: suspend () -> TrackLanguagePreferences = { TrackLanguagePreferences() },
) : PlaybackCommands {
    override suspend fun playLive(
        account: Account,
        channel: LiveChannel,
        requestedFormat: String?,
    ): String {
        val credentials = sessionRepository.credentialsFor(account.id)
        val format = requestedFormat ?: XtreamStreamUrlFactory.selectFormat(account, channel)
        /*
         * A playlist channel carries its own address, and it is the only way to start it: there is
         * no stream id to build a URL from and no account to put in one. `directSource` is also the
         * whole test for which kind of channel this is - Xtream never fills it, because
         * `XtreamJsonParser` leaves `direct_source` unparsed - so no flag has to be threaded here.
         */
        val streamUrl = channel.directSource
            ?: XtreamStreamUrlFactory.buildLiveUrl(credentials, channel.id, format)
        val metadata = MediaMetadata.Builder()
            .setTitle(channel.name)
            .setArtworkUri(channel.logoUrl?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_TV_CHANNEL)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(PlaybackMediaId.Live(account.id, channel.id).encode())
            .setUri(streamUrl)
            .setMimeType(if (format == "m3u8") MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T)
            .setMediaMetadata(metadata)
            .apply {
                // Only a playlist channel has any; see [PlaybackRequestHeaders] for why they cannot
                // simply be set on the player.
                PlaybackRequestHeaders.toExtras(channel.streamHeaders)?.let { extras ->
                    setRequestMetadata(
                        MediaItem.RequestMetadata.Builder().setExtras(extras).build(),
                    )
                }
            }
            .build()
        startItem(item)
        return format
    }

    /**
     * Starts a Movie at [startPositionMs].
     *
     * The position is handed to the controller together with the item, before `prepare`, so
     * playback opens at the resume point instead of starting from zero and seeking visibly once
     * the first frame is up.
     *
     * No MIME type is declared: the container extension is already in the URL and a wrong explicit
     * type would make a playable file fail. The authenticated URL exists only inside this item.
     */
    override suspend fun playMovie(
        account: Account,
        movie: MovieSummary,
        startPositionMs: Long,
    ): String {
        val credentials = sessionRepository.credentialsFor(account.id)
        val extension = XtreamStreamUrlFactory.selectVodExtension(movie.containerExtension)
        val streamUrl = XtreamStreamUrlFactory.buildMovieUrl(credentials, movie.id, extension)
        val metadata = MediaMetadata.Builder()
            .setTitle(movie.name)
            .setArtworkUri(movie.posterUrl?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_MOVIE)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(PlaybackMediaId.Movie(account.id, movie.id).encode())
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
        startItem(item, startPositionMs.coerceAtLeast(0L))
        return extension
    }

    /**
     * Starts an episode at [startPositionMs], following [playMovie] exactly: same resume handling,
     * same absence of a declared MIME type, and the same rule that the authenticated URL exists
     * only inside this item.
     *
     * The episode is addressed by the provider's own ID. Season and episode numbers appear in the
     * title only — using them to build a URL would silently play the wrong file wherever a
     * provider renumbers.
     */
    override suspend fun playEpisode(
        account: Account,
        episode: SeriesEpisode,
        startPositionMs: Long,
    ): String {
        val credentials = sessionRepository.credentialsFor(account.id)
        val extension = XtreamStreamUrlFactory.selectVodExtension(episode.containerExtension)
        val streamUrl = XtreamStreamUrlFactory.buildEpisodeUrl(credentials, episode.id, extension)
        val metadata = MediaMetadata.Builder()
            .setTitle(episode.displayLabel)
            .setArtworkUri(episode.stillUrl?.let(Uri::parse))
            .setMediaType(MediaMetadata.MEDIA_TYPE_TV_SHOW)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(PlaybackMediaId.Episode(account.id, episode.id).encode())
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
        startItem(item, startPositionMs.coerceAtLeast(0L))
        return extension
    }

    /**
     * Loads [item] and starts it, with the remembered track languages already in place.
     *
     * The languages are applied after the item and before `prepare`, which is the only point where
     * they cost nothing: the first track selection pass then already picks the right audio, instead
     * of the viewer hearing the wrong language for a second and the player switching under them.
     *
     * The same reasoning as the resume position, which is handed over in the same call for the same
     * reason.
     */
    private suspend fun startItem(item: MediaItem, startPositionMs: Long? = null) {
        val languages = trackLanguages()
        connection.awaitController().run {
            if (startPositionMs == null) setMediaItem(item) else setMediaItem(item, startPositionMs)
            applyTrackLanguages(languages)
            prepare()
            play()
        }
    }

    override fun retry() = connection.retry()
    override fun reconnect() = connection.reconnect()
    override fun togglePlayPause(): Boolean = connection.togglePlayPause()
    override fun seekBy(deltaMs: Long): Boolean = connection.seekBy(deltaMs)
    override fun beginTemporarySpeed(speed: Float): Boolean = connection.beginTemporarySpeed(speed)
    override fun endTemporarySpeed() = connection.endTemporarySpeed()
    override fun stopAndClear() = connection.stopAndClear()
}
