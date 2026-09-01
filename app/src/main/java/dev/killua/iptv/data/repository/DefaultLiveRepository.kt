package dev.killua.iptv.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.killua.iptv.core.database.AccountDao
import dev.killua.iptv.core.database.LikeSearchTerm
import dev.killua.iptv.core.database.LiveCategoryEntity
import dev.killua.iptv.core.database.LiveChannelEntity
import dev.killua.iptv.core.database.LiveDao
import dev.killua.iptv.core.database.LiveQueryFactory
import dev.killua.iptv.core.database.RecentChannelEntity
import dev.killua.iptv.core.database.toDomain
import dev.killua.iptv.data.xtream.XtreamLanguageTagger
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveFilter
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.LiveSyncResult
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import dev.killua.iptv.domain.model.LibrarySource

class DefaultLiveRepository(
    private val liveDao: LiveDao,
    private val accountDao: AccountDao,
    private val accountData: AccountDataCoordinator,
    private val sessionRepositoryProvider: () -> SessionRepository,
    private val remote: XtreamRemoteDataSource,
    /**
     * Where a playlist account's listing comes from. See [LiveListingSource] for why there are two.
     */
    private val playlistSource: LiveListingSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : LiveRepository, AccountDataCleaner {
    private val generation = AtomicLong(nowMillis())

    /**
     * Guide entries by account and channel. Cleared with the account for the same reason the Room
     * tables are: nothing belonging to a signed-out account may outlive it, in memory either.
     */
    private val epgCache = ConcurrentHashMap<EpgKey, CachedEpg>()

    override fun observeCategories(accountId: String): Flow<List<LiveCategory>> =
        liveDao.observeCategories(accountId).map { rows -> rows.map { it.toDomain() } }

    override fun observeLanguages(accountId: String): Flow<List<String>> =
        liveDao.observeLanguages(accountId)

    override fun channels(
        accountId: String,
        filter: LiveFilter,
    ): Flow<PagingData<LiveChannel>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 20,
            initialLoadSize = 90,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            val query = LiveQueryFactory.build(accountId, filter)
            liveDao.pageChannels(SimpleSQLiteQuery(query.sql, query.arguments.toTypedArray()))
        },
    ).flow.map { paging -> paging.map { it.toDomain() } }

    override fun observeRecent(accountId: String, limit: Int): Flow<List<LiveChannel>> =
        liveDao.observeRecent(accountId, limit).map { rows -> rows.map { it.toDomain() } }

    /**
     * Guide for one channel, memory-cached for [EPG_CACHE_TTL_MS].
     *
     * Not persisted: a guide is stale within the hour, it is only ever read for the channel on
     * screen, and giving it a table would mean a schema change plus an expiry sweep for data whose
     * whole value is being current. The cache exists so that returning to the same channel, or a
     * rotation, does not re-ask the provider.
     *
     * A failure is swallowed into an empty list on purpose: the guide is decoration around
     * playback, and a provider that cannot answer must not turn into a playback error.
     */
    override suspend fun epg(accountId: String, streamId: String): List<EpgEntry> {
        val key = EpgKey(accountId, streamId)
        val now = nowMillis()
        epgCache[key]?.takeIf { now - it.fetchedAtMillis < EPG_CACHE_TTL_MS }?.let {
            return it.entries
        }
        return try {
            val credentials = sessionRepositoryProvider().credentialsFor(accountId)
            val entries = remote.shortEpg(credentials, streamId, EPG_ENTRY_LIMIT)
            epgCache[key] = CachedEpg(entries, now)
            entries
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Global search over the cached library.
     *
     * A term shorter than the global minimum returns nothing rather than scanning the table; one
     * character would match most of a six-figure library and answer nothing useful. One row beyond
     * [limit] is read so the caller can say "there are more" without a second counting scan.
     */
    override suspend fun search(
        accountId: String,
        term: String,
        limit: Int,
    ): SearchSection<LiveChannel> {
        val pattern = LikeSearchTerm.globalContainsPattern(term) ?: return SearchSection()
        val rows = liveDao.searchChannels(accountId, pattern, limit + 1)
        return SearchSection(
            items = rows.take(limit).map { it.toDomain() },
            hasMore = rows.size > limit,
        )
    }

    override suspend fun getChannel(accountId: String, streamId: String): LiveChannel? {
        val row = liveDao.getChannel(accountId, streamId) ?: return null
        return row.toDomain(liveDao.getLastWatched(accountId, streamId))
    }

    /**
     * Refreshes the cached library.
     *
     * Channels stream from the network straight into the database in batches, so a provider with
     * tens of thousands of channels never has to exist in memory as one collection. The account
     * lock is therefore held for the whole download; see `DefaultMovieRepository.refresh` for the
     * reasoning behind that trade-off.
     */
    override suspend fun hasCachedLibrary(accountId: String): Boolean =
        liveDao.countChannels(accountId) > 0

    override suspend fun refresh(accountId: String, onProgress: (Int) -> Unit): LiveSyncResult {
        val credentials = sessionRepositoryProvider().credentialsFor(accountId)
        // Which of the two listings this account has. Everything after this line is identical for
        // both, which is the whole point of the seam.
        val source: LiveListingSource =
            if (credentials.source == LibrarySource.Playlist) playlistSource else remote
        val categories = source.liveCategories(credentials)

        // The provider category is the most reliable language signal, so resolve it once and let
        // a channel-name tag fill in only where the category says nothing.
        val languageByCategory = categories.associate { category ->
            category.id to XtreamLanguageTagger.languageOfCategory(category.name)
        }

        /*
         * The groups a playlist keeps inside its entries.
         *
         * An M3U has no category listing to fetch: `group-title` sits on each `#EXTINF` line, so
         * the only way to know the set is to watch it go past. Collected here rather than by the
         * source, which keeps [LiveListingSource] to the two calls it already had - and for Xtream
         * this stays empty, because its categories arrived up front.
         */
        val discoveredCategories = LinkedHashSet<String>()

        val result = source.withLiveChannels(credentials) { channels ->
            accountData.commitTransaction(accountId) {
                val syncGeneration = generation.updateAndGet { previous ->
                    maxOf(previous + 1L, nowMillis())
                }
                val finishedAt = nowMillis()

                categories.map { category ->
                    LiveCategoryEntity(
                        accountId = accountId,
                        remoteCategoryId = category.id,
                        name = category.name,
                        sortOrder = category.sortOrder,
                        syncGeneration = syncGeneration,
                    )
                }.chunked(UPSERT_BATCH_SIZE).forEach { liveDao.upsertCategories(it) }

                var channelCount = 0
                channels.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
                    liveDao.upsertChannels(
                        batch.map { channel ->
                            LiveChannelEntity(
                                accountId = accountId,
                                remoteStreamId = channel.id,
                                remoteCategoryId = channel.categoryId,
                                name = channel.name,
                                sortName = LiveChannelEntity.sortNameOf(channel.name),
                                logoUrl = channel.logoUrl,
                                epgChannelId = channel.epgChannelId,
                                containerExtension = channel.containerExtension,
                                languageTag = channel.categoryId?.let { languageByCategory[it] }
                                    ?: XtreamLanguageTagger.languageOfTitle(channel.name),
                                providerOrder = channel.providerOrder,
                                syncGeneration = syncGeneration,
                                directSource = channel.directSource,
                                streamUserAgent = channel.streamHeaders?.userAgent,
                                streamReferrer = channel.streamHeaders?.referrer,
                            )
                        },
                    )
                    batch.forEach { channel ->
                        channel.categoryId?.let { discoveredCategories += it }
                    }
                    channelCount += batch.size
                    onProgress(channelCount)
                }

                /*
                 * A playlist's categories, written after the channels because that is when they are
                 * known. Same transaction and same generation, so the stale sweep below treats them
                 * exactly as it treats the Xtream ones.
                 *
                 * The group title is both the id and the name: a playlist has no category ids, and
                 * inventing one would break the moment the file is read again.
                 */
                val playlistCategories = if (categories.isEmpty()) {
                    discoveredCategories.mapIndexed { index, group ->
                        LiveCategoryEntity(
                            accountId = accountId,
                            remoteCategoryId = group,
                            name = group,
                            sortOrder = index,
                            syncGeneration = syncGeneration,
                        )
                    }
                } else {
                    emptyList()
                }
                playlistCategories.chunked(UPSERT_BATCH_SIZE).forEach { liveDao.upsertCategories(it) }

                liveDao.deleteStaleChannels(accountId, syncGeneration)
                liveDao.deleteStaleCategories(accountId, syncGeneration)
                accountDao.setLastLiveSync(accountId, finishedAt)
                LiveSyncResult(
                    maxOf(categories.size, playlistCategories.size),
                    channelCount,
                    finishedAt,
                )
            }
        }
        sessionRepositoryProvider().refreshCachedAccount()
        return result
    }

    override suspend fun markRecent(accountId: String, streamId: String) =
        accountData.commit(accountId) {
            liveDao.upsertRecent(RecentChannelEntity(accountId, streamId, nowMillis()))
        }

    // AccountDataCleaner: invoked by the coordinator inside its lock and transaction.
    override suspend fun clearAllAccountData() {
        epgCache.clear()
        liveDao.deleteAllRecents()
        liveDao.deleteAllChannels()
        liveDao.deleteAllCategories()
    }

    override suspend fun clearAllAccountDataExcept(accountId: String) {
        epgCache.keys.removeAll { it.accountId != accountId }
        liveDao.deleteAllRecentsExcept(accountId)
        liveDao.deleteAllChannelsExcept(accountId)
        liveDao.deleteAllCategoriesExcept(accountId)
    }

    private data class EpgKey(val accountId: String, val streamId: String)

    private data class CachedEpg(val entries: List<EpgEntry>, val fetchedAtMillis: Long)

    private companion object {
        const val UPSERT_BATCH_SIZE = 500

        /** Long enough that returning to a channel or rotating does not re-ask the provider. */
        const val EPG_CACHE_TTL_MS = 5 * 60 * 1_000L

        /** Now plus a few hours of "what's next"; the screen shows two of them. */
        const val EPG_ENTRY_LIMIT = 6
    }
}
