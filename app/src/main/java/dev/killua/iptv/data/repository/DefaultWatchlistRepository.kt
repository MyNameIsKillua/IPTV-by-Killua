package dev.killua.iptv.data.repository

import dev.killua.iptv.core.database.WatchlistDao
import dev.killua.iptv.core.database.WatchlistEntity
import dev.killua.iptv.core.database.WatchlistProjection
import dev.killua.iptv.domain.model.WatchlistEntry
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The saved list, backed by one account-scoped table.
 *
 * Writes go through [AccountDataCoordinator] like every other account-scoped write, so a save that
 * lands after logout or an account swap is rejected instead of repopulating deleted data. It
 * registers as an [AccountDataCleaner] so the coordinator clears the table inside the same
 * transaction as everything else.
 */
class DefaultWatchlistRepository(
    private val watchlistDao: WatchlistDao,
    private val accountData: AccountDataCoordinator,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : WatchlistRepository, AccountDataCleaner {

    override fun observe(accountId: String, limit: Int): Flow<List<WatchlistEntry>> = watchlistDao
        .observeSaved(
            accountId = accountId,
            movieType = MOVIE_TYPE,
            seriesType = SERIES_TYPE,
            channelType = CHANNEL_TYPE,
            limit = limit,
        )
        .map { rows -> rows.mapNotNull(WatchlistProjection::toEntry) }

    override fun observeIsSaved(
        accountId: String,
        kind: WatchlistKind,
        contentId: String,
    ): Flow<Boolean> = watchlistDao.observeIsSaved(accountId, kind.contentType, contentId)

    override fun observeSavedIds(accountId: String, kind: WatchlistKind): Flow<Set<String>> =
        watchlistDao.observeSavedIds(accountId, kind.contentType).map { it.toSet() }

    override suspend fun setSaved(
        accountId: String,
        kind: WatchlistKind,
        contentId: String,
        saved: Boolean,
    ) {
        accountData.commit(accountId) {
            if (!saved) {
                watchlistDao.delete(accountId, kind.contentType, contentId)
                return@commit
            }
            watchlistDao.upsert(
                WatchlistEntity(
                    accountId = accountId,
                    contentType = kind.contentType,
                    contentId = contentId,
                    addedAtEpochMillis = nowMillis(),
                ),
            )
        }
    }

    // AccountDataCleaner: invoked by the coordinator inside its lock and transaction.
    override suspend fun clearAllAccountData() {
        watchlistDao.deleteAll()
    }

    override suspend fun clearAllAccountDataExcept(accountId: String) {
        watchlistDao.deleteAllExcept(accountId)
    }

    private companion object {
        // The same discriminators watch_progress uses, so one identity scheme covers the app.
        const val MOVIE_TYPE = "movie"
        const val SERIES_TYPE = "series"
        const val CHANNEL_TYPE = "channel"
    }
}

private val WatchlistKind.contentType: String
    get() = when (this) {
        WatchlistKind.Movie -> "movie"
        WatchlistKind.Series -> "series"
        WatchlistKind.Channel -> "channel"
    }

/**
 * Null for a row whose stored type is not one this build knows.
 *
 * That cannot happen today, but dropping the row is the safe reading: a saved entry the UI cannot
 * open is worse than one that quietly does not appear.
 */
private fun WatchlistProjection.toEntry(): WatchlistEntry? {
    val kind = when (contentType) {
        "movie" -> WatchlistKind.Movie
        "series" -> WatchlistKind.Series
        "channel" -> WatchlistKind.Channel
        else -> return null
    }
    return WatchlistEntry(
        contentId = contentId,
        kind = kind,
        title = name,
        artworkUrl = artworkUrl,
        addedAtEpochMillis = addedAtEpochMillis,
    )
}
