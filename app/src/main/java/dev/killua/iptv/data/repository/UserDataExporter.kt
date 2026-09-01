package dev.killua.iptv.data.repository

import dev.killua.iptv.core.database.UserDataDao
import dev.killua.iptv.domain.model.XtreamCredentials
import dev.killua.iptv.domain.userdata.MarkRecord
import dev.killua.iptv.domain.userdata.ProgressRecord
import dev.killua.iptv.domain.userdata.UserDataExport
import dev.killua.iptv.domain.userdata.UserDataExportCodec
import dev.killua.iptv.domain.userdata.WatchlistRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Assembles the export for one account.
 *
 * Runs outside the account-data lock on purpose. This only reads, and holding the lock would mean a
 * running library refresh could block the export for minutes on a six-figure provider. The cost is
 * that an export taken mid-refresh may miss a position written a second later, which is not worth a
 * lock.
 *
 * The credentials are touched exactly once, to hash the host and username into a fingerprint. They
 * are never returned, never logged, and never written to the file.
 */
class UserDataExporter(
    private val dao: UserDataDao,
    private val credentialsFor: suspend (accountId: String) -> XtreamCredentials,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun export(accountId: String): UserDataExport = withContext(Dispatchers.IO) {
        val credentials = credentialsFor(accountId)
        // Which series each episode belongs to, so the other device can name what it is looking at.
        // Only this device knows: it cached the episode list when the series was opened, and no
        // Xtream listing indexes episodes at all.
        val seriesOfEpisode = dao.episodeSeriesIds(accountId)
            .associate { it.remoteEpisodeId to it.remoteSeriesId }
        UserDataExport(
            exportedAtEpochMillis = now(),
            accountFingerprint = UserDataExportCodec.fingerprint(
                serverHost = hostOf(credentials.serverUrl),
                username = credentials.username,
            ),
            watchProgress = dao.watchProgress(accountId).map {
                ProgressRecord(
                    contentType = it.contentType,
                    contentId = it.contentId,
                    positionMs = it.positionMs,
                    durationMs = it.durationMs,
                    completed = it.completed,
                    updatedAtEpochMillis = it.updatedAtEpochMillis,
                    seriesId = seriesOfEpisode[it.contentId],
                )
            },
            movieFavorites = dao.movieFavorites(accountId).map {
                MarkRecord(it.remoteStreamId, it.favoritedAtEpochMillis)
            },
            seriesFavorites = dao.seriesFavorites(accountId).map {
                MarkRecord(it.remoteSeriesId, it.favoritedAtEpochMillis)
            },
            watchlist = dao.watchlist(accountId).map {
                WatchlistRecord(it.contentType, it.contentId, it.addedAtEpochMillis)
            },
            recentChannels = dao.recentChannels(accountId).map {
                MarkRecord(it.remoteStreamId, it.lastWatchedAtEpochMillis)
            },
        )
    }

    /**
     * The host alone, so the same account reached over http or https, with or without a trailing
     * slash, fingerprints identically. Falls back to the whole string when it cannot be parsed,
     * which keeps a fingerprint stable rather than empty.
     */
    private fun hostOf(serverUrl: String): String =
        runCatching { java.net.URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: serverUrl
}
