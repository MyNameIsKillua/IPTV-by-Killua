package dev.killua.iptv.data.repository

import dev.killua.iptv.core.database.MovieFavoriteEntity
import dev.killua.iptv.core.database.RecentChannelEntity
import dev.killua.iptv.core.database.SeriesFavoriteEntity
import dev.killua.iptv.core.database.UserDataDao
import dev.killua.iptv.core.database.WatchProgressEntity
import dev.killua.iptv.core.database.WatchlistEntity
import dev.killua.iptv.domain.model.XtreamCredentials
import dev.killua.iptv.domain.userdata.MarkRecord
import dev.killua.iptv.domain.userdata.ProgressRecord
import dev.killua.iptv.domain.userdata.UserDataExportCodec
import dev.killua.iptv.domain.userdata.UserDataImportPlan
import dev.killua.iptv.domain.userdata.UserDataImportResult
import dev.killua.iptv.domain.userdata.UserDataMerge
import dev.killua.iptv.domain.userdata.WatchlistRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * Reads an exported file back into the active account.
 *
 * Split into [plan] and [apply] on purpose. Planning reads and decides but writes nothing, so the
 * viewer can be shown what an import would change and turn it down. Nothing about their data moves
 * until they say yes.
 *
 * Unlike the export, this writes, so [apply] runs inside `AccountDataCoordinator.commitTransaction`.
 * It therefore serializes with logout, account replacement and library refresh, and lands as one
 * all-or-nothing transaction: a logout racing an import cannot leave half a merge behind.
 */
class UserDataImporter(
    private val dao: UserDataDao,
    private val accountData: AccountDataCoordinator,
    private val credentialsFor: suspend (accountId: String) -> XtreamCredentials,
) {
    /**
     * Works out what [document] would change, without touching anything.
     *
     * The fingerprint is checked before any merge is computed. Merging one provider account's
     * history into another cannot be undone, and the fingerprint exists exactly so that cannot
     * happen by accident.
     */
    suspend fun plan(accountId: String, document: String): UserDataImportPlan =
        withContext(Dispatchers.IO) {
            val decoded = UserDataExportCodec.decode(document)
            if (decoded !is UserDataImportResult.Ok) {
                return@withContext UserDataImportPlan.Unreadable(decoded)
            }
            val credentials = credentialsFor(accountId)
            val expected = UserDataExportCodec.fingerprint(
                serverHost = hostOf(credentials.serverUrl),
                username = credentials.username,
            )
            if (decoded.export.accountFingerprint != expected) {
                return@withContext UserDataImportPlan.WrongAccount
            }

            UserDataImportPlan.Ready(
                export = decoded.export,
                progress = UserDataMerge.progress(
                    local = dao.watchProgress(accountId).map { it.toRecord() },
                    imported = decoded.export.watchProgress,
                ),
                movieFavorites = UserDataMerge.marks(
                    local = dao.movieFavorites(accountId)
                        .map { MarkRecord(it.remoteStreamId, it.favoritedAtEpochMillis) },
                    imported = decoded.export.movieFavorites,
                ),
                seriesFavorites = UserDataMerge.marks(
                    local = dao.seriesFavorites(accountId)
                        .map { MarkRecord(it.remoteSeriesId, it.favoritedAtEpochMillis) },
                    imported = decoded.export.seriesFavorites,
                ),
                watchlist = UserDataMerge.watchlist(
                    local = dao.watchlist(accountId)
                        .map { WatchlistRecord(it.contentType, it.contentId, it.addedAtEpochMillis) },
                    imported = decoded.export.watchlist,
                ),
                recentChannels = UserDataMerge.marks(
                    local = dao.recentChannels(accountId)
                        .map { MarkRecord(it.remoteStreamId, it.lastWatchedAtEpochMillis) },
                    imported = decoded.export.recentChannels,
                ),
            )
        }

    /** Writes an approved plan and reports how many rows changed. */
    suspend fun apply(accountId: String, plan: UserDataImportPlan.Ready): Int =
        accountData.commitTransaction(accountId) {
            dao.upsertWatchProgress(plan.progress.map { it.toEntity(accountId) })
            dao.upsertMovieFavorites(
                plan.movieFavorites.map {
                    MovieFavoriteEntity(accountId, it.contentId, it.atEpochMillis)
                },
            )
            dao.upsertSeriesFavorites(
                plan.seriesFavorites.map {
                    SeriesFavoriteEntity(accountId, it.contentId, it.atEpochMillis)
                },
            )
            dao.upsertWatchlist(
                plan.watchlist.map {
                    WatchlistEntity(accountId, it.contentType, it.contentId, it.addedAtEpochMillis)
                },
            )
            dao.upsertRecentChannels(
                plan.recentChannels.map {
                    RecentChannelEntity(accountId, it.contentId, it.atEpochMillis)
                },
            )
            plan.changeCount
        }

    private fun WatchProgressEntity.toRecord() = ProgressRecord(
        contentType = contentType,
        contentId = contentId,
        positionMs = positionMs,
        durationMs = durationMs,
        completed = completed,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    private fun ProgressRecord.toEntity(accountId: String) = WatchProgressEntity(
        accountId = accountId,
        contentType = contentType,
        contentId = contentId,
        positionMs = positionMs,
        durationMs = durationMs,
        completed = completed,
        updatedAtEpochMillis = updatedAtEpochMillis,
    )

    /** Host only, so the fingerprint matches the one the export wrote. See `UserDataExporter`. */
    private fun hostOf(serverUrl: String): String =
        runCatching { URI(serverUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: serverUrl
}
