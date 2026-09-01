package dev.killua.iptv.core.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.killua.iptv.domain.model.StreamHeaders
import okhttp3.OkHttpClient

/**
 * The player's source of media, which reads a playlist channel's headers off the item.
 *
 * Media3 keeps request headers on a `DataSource`, and a `DataSource.Factory` is handed to the
 * player once - so the usual arrangement cannot answer "these headers for this channel and
 * different ones for the next". This sits one level up, where each item is visible, and builds the
 * source for that item with the headers it named.
 *
 * **Xtream is untouched.** Its items carry no extras, so they take [plain] - the single factory
 * built once, exactly what the service used before this existed. Only a playlist channel gets a
 * factory of its own, and only when it actually named something.
 *
 * Why it matters: measured against a real public playlist, roughly one channel in sixteen names a
 * user agent or a referrer, and its server answers 403 without them - which arrives looking exactly
 * like a stream that is simply broken.
 */
@UnstableApi
class HeaderAwareMediaSourceFactory(
    private val context: Context,
    private val client: OkHttpClient,
    private val userAgent: String,
    private val retryCount: Int,
) : MediaSource.Factory {
    private var drmProvider: DrmSessionManagerProvider? = null
    private var errorPolicy: LoadErrorHandlingPolicy? = null

    /** The one every Xtream item uses, built once because nothing about it varies. */
    private val plain by lazy { factoryFor(headers = null) }

    override fun getSupportedTypes(): IntArray = plain.supportedTypes

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply { drmProvider = provider }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply { errorPolicy = policy }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val headers = PlaybackRequestHeaders.fromItem(mediaItem)
            ?: return plain.createMediaSource(mediaItem)
        return factoryFor(headers).createMediaSource(mediaItem)
    }

    private fun factoryFor(headers: StreamHeaders?): DefaultMediaSourceFactory {
        val data = OkHttpDataSource.Factory(client)
            // A user agent is not an ordinary header here: OkHttpDataSource has its own slot for it,
            // and setting both would send two.
            .setUserAgent(headers?.userAgent ?: userAgent)
        headers?.referrer?.let {
            // `Referer`, with the spelling the HTTP standard froze in 1996. Playlists write the
            // option as `http-referrer`, which is the correct English and the wrong header name.
            data.setDefaultRequestProperties(mapOf("Referer" to it))
        }
        return DefaultMediaSourceFactory(context)
            .setDataSourceFactory(data)
            .setLoadErrorHandlingPolicy(
                errorPolicy ?: DefaultLoadErrorHandlingPolicy(retryCount),
            )
            .apply { drmProvider?.let(::setDrmSessionManagerProvider) }
    }
}
