package dev.killua.iptv.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.killua.iptv.core.database.LikeSearchTerm
import dev.killua.iptv.core.database.SeriesCategoryEntity
import dev.killua.iptv.core.database.SeriesDao
import dev.killua.iptv.core.database.SeriesDetailsEntity
import dev.killua.iptv.core.database.SeriesEntity
import dev.killua.iptv.core.database.SeriesEpisodeEntity
import dev.killua.iptv.core.database.SeriesFavoriteEntity
import dev.killua.iptv.core.database.SeriesQueryFactory
import dev.killua.iptv.core.database.WatchProgressEntity
import dev.killua.iptv.core.database.toDomain
import dev.killua.iptv.data.xtream.XtreamLanguageTagger
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.progress.WatchProgressPolicy
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.SeriesSyncResult
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

/**
 * Cached-first Series library.
 *
 * Identical in shape to [DefaultMovieRepository]: the listing streams from the network straight
 * into the database in batches inside one generation-marked transaction through
 * [AccountDataCoordinator], which rejects the write if the account was logged out or replaced
 * meanwhile. Any download, parse, or database failure leaves the previous cache intact.
 *
 * Details and episodes carry no sync generation, so a listing refresh can never replace rich
 * metadata with nulls or drop episodes a provider temporarily omits from the listing.
 */
class DefaultSeriesRepository(
    private val seriesDao: SeriesDao,
    private val accountData: AccountDataCoordinator,
    private val sessionRepositoryProvider: () -> SessionRepository,
    private val remote: XtreamRemoteDataSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SeriesRepository, AccountDataCleaner {
    private val generation = AtomicLong(nowMillis())

    override fun observeCategories(accountId: String): Flow<List<SeriesCategory>> =
        seriesDao.observeCategories(accountId).map { rows -> rows.map { it.toDomain() } }

    override fun observeLanguages(accountId: String): Flow<List<String>> =
        seriesDao.observeLanguages(accountId)

    override fun series(
        accountId: String,
        filter: SeriesFilter,
    ): Flow<PagingData<SeriesSummary>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 20,
            initialLoadSize = 90,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            val query = SeriesQueryFactory.build(accountId, filter)
            seriesDao.pageSeries(SimpleSQLiteQuery(query.sql, query.arguments.toTypedArray()))
        },
    ).flow.map { paging -> paging.map { it.toDomain() } }

    override fun observeRecentlyAdded(
        accountId: String,
        limit: Int,
    ): Flow<List<RecentlyAddedEntry>> = seriesDao.observeRecentlyAdded(accountId, limit).map { rows ->
        rows.mapNotNull { entity ->
            val addedAt = entity.lastModifiedEpochSeconds ?: return@mapNotNull null
            RecentlyAddedEntry(
                contentId = entity.remoteSeriesId,
                kind = ResumableKind.Series,
                title = entity.name,
                posterUrl = entity.posterUrl,
                addedAtEpochSeconds = addedAt,
            )
        }
    }

    override fun observeContinueWatching(
        accountId: String,
        limit: Int,
    ): Flow<List<ContinueWatchingEntry>> = seriesDao
        .observeContinueWatching(accountId, SeriesQueryFactory.EPISODE_CONTENT_TYPE, limit)
        .map { rows -> rows.map { it.toDomain(ResumableKind.Series) } }

    override fun observeIsFavorite(accountId: String, seriesId: String): Flow<Boolean> =
        seriesDao.observeIsFavorite(accountId, seriesId)

    override suspend fun setFavorite(accountId: String, seriesId: String, favorite: Boolean) =
        accountData.commit(accountId) {
            if (favorite) {
                seriesDao.upsertFavorite(SeriesFavoriteEntity(accountId, seriesId, nowMillis()))
            } else {
                seriesDao.deleteFavorite(accountId, seriesId)
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
    ): SearchSection<SeriesSummary> {
        val pattern = LikeSearchTerm.globalContainsPattern(term) ?: return SearchSection()
        val rows = seriesDao.searchSeries(accountId, pattern, limit + 1)
        return SearchSection(
            items = rows.take(limit).map { it.toDomain() },
            hasMore = rows.size > limit,
        )
    }

    override suspend fun getSeries(accountId: String, seriesId: String): SeriesSummary? =
        seriesDao.getSeries(accountId, seriesId)?.toDomain()

    override suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisode? =
        seriesDao.getEpisode(accountId, episodeId)?.toDomain()

    /**
     * Uses the same ordering the details screen shows, so "next" always means the row below the
     * one being watched — including across a season boundary, which is where a viewer most wants
     * it. Season and episode numbers order the list but never identify an episode; the provider's
     * own ID does.
     */
    override suspend fun nextEpisode(accountId: String, episodeId: String): SeriesEpisode? =
        episodeRelativeTo(accountId, episodeId, offset = 1)

    override suspend fun previousEpisode(accountId: String, episodeId: String): SeriesEpisode? =
        episodeRelativeTo(accountId, episodeId, offset = -1)

    /** One list walk serves both directions, so the two controls cannot disagree about order. */
    private suspend fun episodeRelativeTo(
        accountId: String,
        episodeId: String,
        offset: Int,
    ): SeriesEpisode? {
        val current = seriesDao.getEpisode(accountId, episodeId) ?: return null
        val episodes = seriesDao.getEpisodes(accountId, current.remoteSeriesId)
        val index = episodes.indexOfFirst { it.remoteEpisodeId == episodeId }
        if (index < 0) return null
        return episodes.getOrNull(index + offset)?.toDomain()
    }

    override suspend fun details(
        accountId: String,
        seriesId: String,
        forceRefresh: Boolean,
    ): SeriesDetails {
        val series = seriesDao.getSeries(accountId, seriesId)
            ?: throw AppFailureException(AppFailure(FailureKind.StreamUnavailable))
        if (!forceRefresh) {
            seriesDao.getDetails(accountId, seriesId)?.let { cached ->
                return cached.toDomain(series, seriesDao.getEpisodes(accountId, seriesId))
            }
        }

        val credentials = sessionRepositoryProvider().credentialsFor(accountId)
        val remoteDetails = remote.seriesDetails(credentials, seriesId)
        val entity = SeriesDetailsEntity(
            accountId = accountId,
            remoteSeriesId = seriesId,
            plot = remoteDetails.plot,
            genre = remoteDetails.genre,
            cast = remoteDetails.cast,
            director = remoteDetails.director,
            backdropUrl = remoteDetails.backdropUrl,
            fetchedAtEpochMillis = nowMillis(),
        )
        val episodes = remoteDetails.episodes.map { episode ->
            SeriesEpisodeEntity(
                accountId = accountId,
                remoteEpisodeId = episode.id,
                remoteSeriesId = seriesId,
                seasonNumber = episode.seasonNumber,
                episodeNumber = episode.episodeNumber,
                title = episode.title,
                containerExtension = episode.containerExtension,
                durationSeconds = episode.durationSeconds,
                plot = episode.plot,
                stillUrl = episode.stillUrl,
            )
        }

        // Replacing the episodes as a set is what removes ones the provider dropped; doing it in
        // the same transaction keeps the screen from ever seeing a series with no episodes.
        accountData.commitTransaction(accountId) {
            seriesDao.upsertDetails(entity)
            seriesDao.deleteEpisodesForSeries(accountId, seriesId)
            episodes.chunked(UPSERT_BATCH_SIZE).forEach { seriesDao.upsertEpisodes(it) }
        }
        return entity.toDomain(series, episodes)
    }

    override suspend fun hasCachedLibrary(accountId: String): Boolean =
        seriesDao.countSeries(accountId) > 0

    /**
     * Refreshes the cached library. See [DefaultMovieRepository.refresh] for why the account lock
     * is held for the whole download rather than only for the commit.
     */
    override suspend fun refresh(accountId: String, onProgress: (Int) -> Unit): SeriesSyncResult {
        val credentials = sessionRepositoryProvider().credentialsFor(accountId)
        val categories = remote.seriesCategories(credentials)

        val languageByCategory = categories.associate { category ->
            category.id to XtreamLanguageTagger.languageOfCategory(category.name)
        }

        return remote.withSeriesSummaries(credentials) { series ->
            accountData.commitTransaction(accountId) {
                val syncGeneration = generation.updateAndGet { previous ->
                    maxOf(previous + 1L, nowMillis())
                }
                val finishedAt = nowMillis()

                categories.map { category ->
                    SeriesCategoryEntity(
                        accountId = accountId,
                        remoteCategoryId = category.id,
                        name = category.name,
                        sortOrder = category.sortOrder,
                        syncGeneration = syncGeneration,
                    )
                }.chunked(UPSERT_BATCH_SIZE).forEach { seriesDao.upsertCategories(it) }

                var seriesCount = 0
                series.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
                    seriesDao.upsertSeries(
                        batch.map { it.toEntity(accountId, languageByCategory, syncGeneration) },
                    )
                    seriesCount += batch.size
                    onProgress(seriesCount)
                }

                seriesDao.deleteStaleSeries(accountId, syncGeneration)
                seriesDao.deleteStaleCategories(accountId, syncGeneration)
                SeriesSyncResult(categories.size, seriesCount, finishedAt)
            }
        }
    }

    private fun SeriesSummary.toEntity(
        accountId: String,
        languageByCategory: Map<String, String?>,
        syncGeneration: Long,
    ) = SeriesEntity(
        accountId = accountId,
        remoteSeriesId = id,
        remoteCategoryId = categoryId,
        name = name,
        sortName = XtreamLanguageTagger.sortNameOf(name),
        posterUrl = posterUrl,
        rating = rating,
        releaseYear = releaseYear,
        lastModifiedEpochSeconds = lastModifiedEpochSeconds,
        languageTag = categoryId?.let { languageByCategory[it] }
            ?: XtreamLanguageTagger.languageOfTitle(name),
        providerOrder = providerOrder,
        syncGeneration = syncGeneration,
    )

    override suspend fun episodeProgress(accountId: String, episodeId: String): WatchProgress? =
        seriesDao
            .getEpisodeProgress(accountId, SeriesQueryFactory.EPISODE_CONTENT_TYPE, episodeId)
            ?.toDomain()

    override fun observeEpisodeProgress(
        accountId: String,
        seriesId: String,
    ): Flow<Map<String, WatchProgress>> = seriesDao
        .observeProgressForSeries(accountId, SeriesQueryFactory.EPISODE_CONTENT_TYPE, seriesId)
        .map { rows -> rows.associate { it.contentId to it.toDomain() } }

    /**
     * Mirrors [DefaultMovieRepository.saveProgress]: implausible controller state is rejected, the
     * account is rechecked inside the lock, and the episode must still exist in the cache — a
     * position for content this account no longer has would be unreachable state.
     */
    override suspend fun saveEpisodeProgress(
        accountId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        if (durationMs <= 0L || positionMs < 0L) return
        val clampedPosition = positionMs.coerceAtMost(durationMs)
        accountData.commit(accountId) {
            if (seriesDao.getEpisode(accountId, episodeId) == null) return@commit
            seriesDao.upsertProgress(
                WatchProgressEntity(
                    accountId = accountId,
                    contentType = SeriesQueryFactory.EPISODE_CONTENT_TYPE,
                    contentId = episodeId,
                    positionMs = clampedPosition,
                    durationMs = durationMs,
                    completed = WatchProgressPolicy.isCompleted(clampedPosition, durationMs),
                    updatedAtEpochMillis = nowMillis(),
                ),
            )
        }
    }

    /** The manual mark, mirroring [DefaultMovieRepository.setWatched] rule for rule. */
    override suspend fun setEpisodeWatched(
        accountId: String,
        episodeId: String,
        watched: Boolean,
    ) {
        accountData.commit(accountId) {
            if (seriesDao.getEpisode(accountId, episodeId) == null) return@commit
            if (!watched) {
                seriesDao.deleteProgress(
                    accountId,
                    SeriesQueryFactory.EPISODE_CONTENT_TYPE,
                    episodeId,
                )
                return@commit
            }
            val existing = seriesDao.getEpisodeProgress(
                accountId,
                SeriesQueryFactory.EPISODE_CONTENT_TYPE,
                episodeId,
            )
            val durationMs = existing?.durationMs?.coerceAtLeast(0L) ?: 0L
            seriesDao.upsertProgress(
                WatchProgressEntity(
                    accountId = accountId,
                    contentType = SeriesQueryFactory.EPISODE_CONTENT_TYPE,
                    contentId = episodeId,
                    positionMs = durationMs,
                    durationMs = durationMs,
                    completed = true,
                    updatedAtEpochMillis = nowMillis(),
                ),
            )
        }
    }

    // AccountDataCleaner: invoked by the coordinator inside its lock and transaction.
    // Episode positions live in the shared `watch_progress` table, which DefaultMovieRepository
    // clears wholesale for every content type; clearing it again here would be redundant.
    override suspend fun clearAllAccountData() {
        seriesDao.deleteAllFavorites()
        seriesDao.deleteAllEpisodes()
        seriesDao.deleteAllDetails()
        seriesDao.deleteAllSeries()
        seriesDao.deleteAllCategories()
    }

    override suspend fun clearAllAccountDataExcept(accountId: String) {
        seriesDao.deleteAllFavoritesExcept(accountId)
        seriesDao.deleteAllEpisodesExcept(accountId)
        seriesDao.deleteAllDetailsExcept(accountId)
        seriesDao.deleteAllSeriesExcept(accountId)
        seriesDao.deleteAllCategoriesExcept(accountId)
    }

    private companion object {
        const val UPSERT_BATCH_SIZE = 500
    }
}
