package dev.killua.iptv.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
import dev.killua.iptv.core.database.LikeSearchTerm
import dev.killua.iptv.core.database.MovieCategoryEntity
import dev.killua.iptv.core.database.MovieDao
import dev.killua.iptv.core.database.MovieDetailsEntity
import dev.killua.iptv.core.database.MovieEntity
import dev.killua.iptv.core.database.MovieFavoriteEntity
import dev.killua.iptv.core.database.MovieQueryFactory
import dev.killua.iptv.core.database.WatchProgressEntity
import dev.killua.iptv.core.database.toDomain
import dev.killua.iptv.data.xtream.XtreamLanguageTagger
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.progress.WatchProgressPolicy
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.MovieSyncResult
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicLong

/**
 * Cached-first Movie library.
 *
 * Mirrors the live-library contract: the provider listing is downloaded outside the shared
 * account lock, then committed in one generation-marked transaction through
 * [AccountDataCoordinator], which rejects the write if the account was logged out or replaced
 * meanwhile. Any download, parse, or database failure leaves the previous cache intact.
 *
 * Details, favorites, and watch progress deliberately carry no sync generation, so a listing
 * refresh can never delete user state or replace rich metadata with nulls when a provider
 * temporarily omits a title.
 */
class DefaultMovieRepository(
    private val movieDao: MovieDao,
    private val accountData: AccountDataCoordinator,
    private val sessionRepositoryProvider: () -> SessionRepository,
    private val remote: XtreamRemoteDataSource,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MovieRepository, AccountDataCleaner {
    private val generation = AtomicLong(nowMillis())

    override fun observeCategories(accountId: String): Flow<List<MovieCategory>> =
        movieDao.observeCategories(accountId).map { rows -> rows.map { it.toDomain() } }

    override fun observeLanguages(accountId: String): Flow<List<String>> =
        movieDao.observeLanguages(accountId)

    override fun movies(
        accountId: String,
        filter: MovieFilter,
    ): Flow<PagingData<MovieSummary>> = Pager(
        config = PagingConfig(
            pageSize = 60,
            prefetchDistance = 20,
            initialLoadSize = 90,
            enablePlaceholders = false,
        ),
        pagingSourceFactory = {
            val query = MovieQueryFactory.build(accountId, filter)
            movieDao.pageMovies(SimpleSQLiteQuery(query.sql, query.arguments.toTypedArray()))
        },
    ).flow.map { paging -> paging.map { it.toDomain() } }

    override fun observeRecentlyAdded(
        accountId: String,
        limit: Int,
    ): Flow<List<RecentlyAddedEntry>> = movieDao.observeRecentlyAdded(accountId, limit).map { rows ->
        rows.mapNotNull { entity ->
            val addedAt = entity.addedAtEpochSeconds ?: return@mapNotNull null
            RecentlyAddedEntry(
                contentId = entity.remoteStreamId,
                kind = ResumableKind.Movie,
                title = entity.name,
                posterUrl = entity.posterUrl,
                addedAtEpochSeconds = addedAt,
            )
        }
    }

    override fun observeContinueWatching(
        accountId: String,
        limit: Int,
    ): Flow<List<ContinueWatchingEntry>> = movieDao
        .observeContinueWatching(accountId, MovieQueryFactory.MOVIE_CONTENT_TYPE, limit)
        .map { rows -> rows.map { it.toDomain(ResumableKind.Movie) } }

    override fun observeIsFavorite(accountId: String, movieId: String): Flow<Boolean> =
        movieDao.observeIsFavorite(accountId, movieId)

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
    ): SearchSection<MovieSummary> {
        val pattern = LikeSearchTerm.globalContainsPattern(term) ?: return SearchSection()
        val rows = movieDao.searchMovies(accountId, pattern, limit + 1)
        return SearchSection(
            items = rows.take(limit).map { it.toDomain() },
            hasMore = rows.size > limit,
        )
    }

    override suspend fun getMovie(accountId: String, movieId: String): MovieSummary? =
        movieDao.getMovie(accountId, movieId)?.toDomain()

    override suspend fun details(
        accountId: String,
        movieId: String,
        forceRefresh: Boolean,
    ): MovieDetails {
        val movie = movieDao.getMovie(accountId, movieId)
            ?: throw AppFailureException(AppFailure(FailureKind.StreamUnavailable))
        if (!forceRefresh) {
            movieDao.getDetails(accountId, movieId)?.let { return it.toDomain(movie) }
        }

        val credentials = sessionRepositoryProvider().credentialsFor(accountId)
        val remoteDetails = remote.movieDetails(credentials, movieId)
        val entity = MovieDetailsEntity(
            accountId = accountId,
            remoteStreamId = movieId,
            plot = remoteDetails.plot,
            genre = remoteDetails.genre,
            cast = remoteDetails.cast,
            director = remoteDetails.director,
            backdropUrl = remoteDetails.backdropUrl,
            durationSeconds = remoteDetails.durationSeconds,
            fetchedAtEpochMillis = nowMillis(),
        )
        accountData.commit(accountId) { movieDao.upsertDetails(entity) }
        return entity.toDomain(movie)
    }

    override suspend fun setFavorite(accountId: String, movieId: String, favorite: Boolean) =
        accountData.commit(accountId) {
            if (favorite) {
                movieDao.upsertFavorite(
                    MovieFavoriteEntity(accountId, movieId, nowMillis()),
                )
            } else {
                movieDao.deleteFavorite(accountId, movieId)
            }
        }

    /**
     * Refreshes the cached library.
     *
     * The listing is streamed from the network straight into the database in batches. A large
     * provider returns six-figure title counts, and materializing them as domain objects and then
     * again as entities exhausted Android's heap even after the response itself was streamed.
     *
     * The trade-off is that the account lock is held for the whole download rather than only for
     * the commit, so a logout issued during a refresh waits for it. That is the acceptable
     * direction: correctness and not crashing beat a faster logout.
     */
    override suspend fun hasCachedLibrary(accountId: String): Boolean =
        movieDao.countMovies(accountId) > 0

    override suspend fun refresh(accountId: String, onProgress: (Int) -> Unit): MovieSyncResult {
        val credentials = sessionRepositoryProvider().credentialsFor(accountId)
        val categories = remote.movieCategories(credentials)

        // The provider category is the most reliable language signal, so resolve it once and let
        // a title tag fill in only where the category says nothing.
        val languageByCategory = categories.associate { category ->
            category.id to XtreamLanguageTagger.languageOfCategory(category.name)
        }

        return remote.withMovieSummaries(credentials) { movies ->
            accountData.commitTransaction(accountId) {
                val syncGeneration = generation.updateAndGet { previous ->
                    maxOf(previous + 1L, nowMillis())
                }
                val finishedAt = nowMillis()

                categories.map { category ->
                    MovieCategoryEntity(
                        accountId = accountId,
                        remoteCategoryId = category.id,
                        name = category.name,
                        languageTag = languageByCategory[category.id],
                        sortOrder = category.sortOrder,
                        syncGeneration = syncGeneration,
                    )
                }.chunked(UPSERT_BATCH_SIZE).forEach { movieDao.upsertCategories(it) }

                var movieCount = 0
                movies.chunked(UPSERT_BATCH_SIZE).forEach { batch ->
                    movieDao.upsertMovies(
                        batch.map { movie -> movie.toEntity(accountId, languageByCategory, syncGeneration) },
                    )
                    movieCount += batch.size
                    onProgress(movieCount)
                }

                movieDao.deleteStaleMovies(accountId, syncGeneration)
                movieDao.deleteStaleCategories(accountId, syncGeneration)
                MovieSyncResult(categories.size, movieCount, finishedAt)
            }
        }
    }

    private fun MovieSummary.toEntity(
        accountId: String,
        languageByCategory: Map<String, String?>,
        syncGeneration: Long,
    ) = MovieEntity(
        accountId = accountId,
        remoteStreamId = id,
        remoteCategoryId = categoryId,
        name = name,
        sortName = XtreamLanguageTagger.sortNameOf(name),
        posterUrl = posterUrl,
        containerExtension = containerExtension,
        rating = rating,
        releaseYear = releaseYear,
        addedAtEpochSeconds = addedAtEpochSeconds,
        languageTag = categoryId?.let { languageByCategory[it] }
            ?: XtreamLanguageTagger.languageOfTitle(name),
        providerOrder = providerOrder,
        syncGeneration = syncGeneration,
    )

    override suspend fun progress(accountId: String, movieId: String): WatchProgress? = movieDao
        .getProgress(accountId, MovieQueryFactory.MOVIE_CONTENT_TYPE, movieId)
        ?.toDomain()

    override fun observeProgress(accountId: String, movieId: String): Flow<WatchProgress?> =
        movieDao
            .observeProgress(accountId, MovieQueryFactory.MOVIE_CONTENT_TYPE, movieId)
            .map { it?.toDomain() }

    override suspend fun saveProgress(
        accountId: String,
        movieId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        // Reject implausible controller state rather than storing a resume point that would
        // later jump the user to the wrong place.
        if (durationMs <= 0L || positionMs < 0L) return
        val clampedPosition = positionMs.coerceAtMost(durationMs)
        accountData.commit(accountId) {
            if (movieDao.getMovie(accountId, movieId) == null) return@commit
            movieDao.upsertProgress(
                WatchProgressEntity(
                    accountId = accountId,
                    contentType = MovieQueryFactory.MOVIE_CONTENT_TYPE,
                    contentId = movieId,
                    positionMs = clampedPosition,
                    durationMs = durationMs,
                    completed = WatchProgressPolicy.isCompleted(clampedPosition, durationMs),
                    updatedAtEpochMillis = nowMillis(),
                ),
            )
        }
    }

    /**
     * The manual mark, which is the one path to completion that playback did not produce.
     *
     * Watched keeps an existing row's duration and moves the position to the end, so the bar the
     * details screen already draws reads full. With no row to build on, the duration stays zero:
     * the mark is the fact worth storing, and inventing a runtime would make a later resume lie.
     * `completed` is set outright rather than run through `WatchProgressPolicy`, because the
     * viewer's statement is the input here, not a playback position.
     */
    override suspend fun setWatched(accountId: String, movieId: String, watched: Boolean) {
        accountData.commit(accountId) {
            if (movieDao.getMovie(accountId, movieId) == null) return@commit
            if (!watched) {
                movieDao.deleteProgress(
                    accountId,
                    MovieQueryFactory.MOVIE_CONTENT_TYPE,
                    movieId,
                )
                return@commit
            }
            val existing = movieDao.getProgress(
                accountId,
                MovieQueryFactory.MOVIE_CONTENT_TYPE,
                movieId,
            )
            val durationMs = existing?.durationMs?.coerceAtLeast(0L) ?: 0L
            movieDao.upsertProgress(
                WatchProgressEntity(
                    accountId = accountId,
                    contentType = MovieQueryFactory.MOVIE_CONTENT_TYPE,
                    contentId = movieId,
                    positionMs = durationMs,
                    durationMs = durationMs,
                    completed = true,
                    updatedAtEpochMillis = nowMillis(),
                ),
            )
        }
    }

    // AccountDataCleaner: invoked by the coordinator inside its lock and transaction.
    override suspend fun clearAllAccountData() {
        movieDao.deleteAllProgress()
        movieDao.deleteAllFavorites()
        movieDao.deleteAllDetails()
        movieDao.deleteAllMovies()
        movieDao.deleteAllCategories()
    }

    override suspend fun clearAllAccountDataExcept(accountId: String) {
        movieDao.deleteAllProgressExcept(accountId)
        movieDao.deleteAllFavoritesExcept(accountId)
        movieDao.deleteAllDetailsExcept(accountId)
        movieDao.deleteAllMoviesExcept(accountId)
        movieDao.deleteAllCategoriesExcept(accountId)
    }

    private companion object {
        const val UPSERT_BATCH_SIZE = 500
    }
}
