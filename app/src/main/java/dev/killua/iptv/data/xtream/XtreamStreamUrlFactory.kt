package dev.killua.iptv.data.xtream

import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.XtreamCredentials
import okhttp3.HttpUrl.Companion.toHttpUrl

object XtreamStreamUrlFactory {
    /**
     * Container extensions accepted for on-demand playback, shared by Movies and Series episodes.
     * The list is deliberately narrow: an arbitrary provider string must never reach a media URL
     * or a decoder.
     */
    val SAFE_VOD_EXTENSIONS = setOf("mp4", "mkv", "avi", "m4v", "mov", "ts", "webm")

    /**
     * Used when a provider omits the container or reports one outside [SAFE_VOD_EXTENSIONS].
     * `mp4` is the Xtream default and keeps such titles playable instead of unreachable; a
     * genuinely different container fails at playback with the normal safe error.
     */
    const val DEFAULT_VOD_EXTENSION = "mp4"

    /** Returns the provider container in canonical form, or null when it is not on the list. */
    fun sanitizeVodExtension(containerExtension: String?): String? = containerExtension
        ?.trim()
        ?.removePrefix(".")
        ?.lowercase()
        ?.takeIf { it in SAFE_VOD_EXTENSIONS }

    fun selectVodExtension(containerExtension: String?): String =
        sanitizeVodExtension(containerExtension) ?: DEFAULT_VOD_EXTENSION

    fun selectFormat(account: Account, channel: LiveChannel): String {
        val advertised = account.allowedOutputFormats.map(String::lowercase).toSet()
        val channelFormat = channel.containerExtension?.lowercase()
        return when {
            channelFormat == "m3u8" && "m3u8" in advertised -> "m3u8"
            "m3u8" in advertised -> "m3u8"
            channelFormat == "ts" -> "ts"
            "ts" in advertised -> "ts"
            channelFormat == "m3u8" -> "m3u8"
            else -> "ts"
        }
    }

    fun buildLiveUrl(
        credentials: XtreamCredentials,
        streamId: String,
        format: String,
    ): String {
        require(format in setOf("m3u8", "ts")) { "Unsupported live stream format" }
        require(streamId.isNotBlank()) { "Stream ID is required" }
        return credentials.serverUrl.toHttpUrl().newBuilder()
            .addPathSegment("live")
            .addPathSegment(credentials.username)
            .addPathSegment(credentials.password)
            .addPathSegment("$streamId.$format")
            .build()
            .toString()
    }

    /**
     * Builds the conventional authenticated Xtream Movie URL. Like the live URL it embeds the
     * username and password in its path, so the result must never be logged, placed in UI state,
     * or carried through navigation.
     */
    fun buildMovieUrl(
        credentials: XtreamCredentials,
        movieId: String,
        extension: String,
    ): String {
        require(extension in SAFE_VOD_EXTENSIONS) { "Unsupported movie container extension" }
        require(movieId.isNotBlank()) { "Movie ID is required" }
        return credentials.serverUrl.toHttpUrl().newBuilder()
            .addPathSegment("movie")
            .addPathSegment(credentials.username)
            .addPathSegment(credentials.password)
            .addPathSegment("$movieId.$extension")
            .build()
            .toString()
    }

    /**
     * Builds the conventional authenticated Xtream episode URL. Episodes live under `series/`
     * rather than `movie/`, and are addressed by the provider's own episode ID — never by season
     * and episode number, which are display values a provider may repeat or renumber.
     *
     * Like every other stream URL it embeds the credentials in its path and must never be logged,
     * placed in UI state, or carried through navigation.
     */
    fun buildEpisodeUrl(
        credentials: XtreamCredentials,
        episodeId: String,
        extension: String,
    ): String {
        require(extension in SAFE_VOD_EXTENSIONS) { "Unsupported episode container extension" }
        require(episodeId.isNotBlank()) { "Episode ID is required" }
        return credentials.serverUrl.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(credentials.username)
            .addPathSegment(credentials.password)
            .addPathSegment("$episodeId.$extension")
            .build()
            .toString()
    }
}
