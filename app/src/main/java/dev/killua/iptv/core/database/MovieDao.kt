package dev.killua.iptv.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Upsert
    suspend fun upsertCategories(categories: List<MovieCategoryEntity>)

    @Upsert
    suspend fun upsertMovies(movies: List<MovieEntity>)

    @Upsert
    suspend fun upsertDetails(details: MovieDetailsEntity)

    @Upsert
    suspend fun upsertFavorite(favorite: MovieFavoriteEntity)

    @Upsert
    suspend fun upsertProgress(progress: WatchProgressEntity)

    @Query(
        """
        SELECT * FROM movie_categories
        WHERE accountId = :accountId
        ORDER BY sortOrder ASC, name COLLATE NOCASE ASC
        """,
    )
    fun observeCategories(accountId: String): Flow<List<MovieCategoryEntity>>

    /**
     * Distinct heuristic languages present in the cached library, so the filter only ever offers
     * values that would actually match something.
     */
    @Query(
        """
        SELECT DISTINCT languageTag FROM movies
        WHERE accountId = :accountId AND languageTag IS NOT NULL
        ORDER BY languageTag ASC
        """,
    )
    fun observeLanguages(accountId: String): Flow<List<String>>

    /**
     * Paged Movie browsing. The statement is assembled by `MovieQueryFactory` because the filter
     * and sort combinations would otherwise need one declared query each. All caller-supplied
     * values are bound as arguments; only fixed, enum-derived fragments are ever concatenated.
     */
    @RawQuery(
        observedEntities = [
            MovieEntity::class,
            MovieFavoriteEntity::class,
            WatchProgressEntity::class,
        ],
    )
    fun pageMovies(query: SupportSQLiteQuery): PagingSource<Int, MovieEntity>

    /** Global search; see [LiveDao.searchChannels] for why one row beyond the limit is read. */
    @Query(
        """
        SELECT * FROM movies
        WHERE accountId = :accountId AND sortName LIKE :pattern ESCAPE '\'
        ORDER BY sortName ASC, remoteStreamId ASC
        LIMIT :limit
        """,
    )
    suspend fun searchMovies(accountId: String, pattern: String, limit: Int): List<MovieEntity>

    @Query(
        """
        SELECT * FROM movies
        WHERE accountId = :accountId AND remoteStreamId = :movieId
        LIMIT 1
        """,
    )
    suspend fun getMovie(accountId: String, movieId: String): MovieEntity?

    @Query(
        """
        SELECT * FROM movie_details
        WHERE accountId = :accountId AND remoteStreamId = :movieId
        LIMIT 1
        """,
    )
    suspend fun getDetails(accountId: String, movieId: String): MovieDetailsEntity?

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM movie_favorites
            WHERE accountId = :accountId AND remoteStreamId = :movieId
        )
        """,
    )
    fun observeIsFavorite(accountId: String, movieId: String): Flow<Boolean>

    @Query("DELETE FROM movie_favorites WHERE accountId = :accountId AND remoteStreamId = :movieId")
    suspend fun deleteFavorite(accountId: String, movieId: String)

    /**
     * Newest first by the provider's own `added` timestamp, which is indexed.
     *
     * Rows without one are left out rather than sorted as zero: a provider that omits the field
     * would otherwise fill this row with whatever happened to be first.
     */
    @Query(
        """
        SELECT * FROM movies
        WHERE accountId = :accountId AND addedAtEpochSeconds IS NOT NULL
        ORDER BY addedAtEpochSeconds DESC
        LIMIT :limit
        """,
    )
    fun observeRecentlyAdded(accountId: String, limit: Int): Flow<List<MovieEntity>>

    @Query(
        """
        SELECT * FROM watch_progress
        WHERE accountId = :accountId AND contentType = :contentType AND contentId = :contentId
        LIMIT 1
        """,
    )
    suspend fun getProgress(
        accountId: String,
        contentType: String,
        contentId: String,
    ): WatchProgressEntity?

    /**
     * Observed so a details screen reflects a position the player wrote while it was in the back
     * stack, instead of showing the value it read when it was first opened.
     */
    @Query(
        """
        SELECT * FROM watch_progress
        WHERE accountId = :accountId AND contentType = :contentType AND contentId = :contentId
        LIMIT 1
        """,
    )
    fun observeProgress(
        accountId: String,
        contentType: String,
        contentId: String,
    ): Flow<WatchProgressEntity?>

    /**
     * Continue Watching. Joining against `movies` means a title the provider removed disappears
     * from the row without its progress being deleted, matching the live-recents behaviour.
     *
     * The last-watched timestamp is projected out rather than dropped, because Home merges this
     * row with the Series one and could not order the result honestly without it.
     */
    @Query(
        """
        SELECT m.remoteStreamId AS remoteId,
               m.name AS name,
               m.posterUrl AS posterUrl,
               p.updatedAtEpochMillis AS lastWatchedAtEpochMillis
        FROM movies m
        INNER JOIN watch_progress p
            ON m.accountId = p.accountId AND m.remoteStreamId = p.contentId
        WHERE m.accountId = :accountId
          AND p.contentType = :contentType
          AND p.completed = 0
          AND p.positionMs > 0
        ORDER BY p.updatedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    fun observeContinueWatching(
        accountId: String,
        contentType: String,
        limit: Int,
    ): Flow<List<ContinueWatchingProjection>>

    @Query(
        """
        SELECT * FROM watch_progress
        WHERE accountId = :accountId AND contentType = :contentType AND contentId IN (:contentIds)
        """,
    )
    suspend fun getProgressFor(
        accountId: String,
        contentType: String,
        contentIds: List<String>,
    ): List<WatchProgressEntity>

    @Query("SELECT COUNT(*) FROM movies WHERE accountId = :accountId")
    suspend fun countMovies(accountId: String): Int

    @Query("DELETE FROM movies WHERE accountId = :accountId AND syncGeneration != :generation")
    suspend fun deleteStaleMovies(accountId: String, generation: Long)

    @Query(
        "DELETE FROM movie_categories WHERE accountId = :accountId AND syncGeneration != :generation",
    )
    suspend fun deleteStaleCategories(accountId: String, generation: Long)

    @Query("DELETE FROM movies")
    suspend fun deleteAllMovies()

    @Query("DELETE FROM movie_categories")
    suspend fun deleteAllCategories()

    @Query("DELETE FROM movie_details")
    suspend fun deleteAllDetails()

    @Query("DELETE FROM movie_favorites")
    suspend fun deleteAllFavorites()

    @Query("DELETE FROM watch_progress")
    suspend fun deleteAllProgress()

    /**
     * Removes one title's stored position, which is how "mark as unwatched" is expressed.
     *
     * Clearing only the `completed` flag would leave the position behind, and the next Play would
     * resume three minutes from the end of something the viewer just said they had not seen.
     */
    @Query(
        """
        DELETE FROM watch_progress
        WHERE accountId = :accountId AND contentType = :contentType AND contentId = :contentId
        """,
    )
    suspend fun deleteProgress(accountId: String, contentType: String, contentId: String)

    @Query("DELETE FROM movies WHERE accountId != :accountId")
    suspend fun deleteAllMoviesExcept(accountId: String)

    @Query("DELETE FROM movie_categories WHERE accountId != :accountId")
    suspend fun deleteAllCategoriesExcept(accountId: String)

    @Query("DELETE FROM movie_details WHERE accountId != :accountId")
    suspend fun deleteAllDetailsExcept(accountId: String)

    @Query("DELETE FROM movie_favorites WHERE accountId != :accountId")
    suspend fun deleteAllFavoritesExcept(accountId: String)

    @Query("DELETE FROM watch_progress WHERE accountId != :accountId")
    suspend fun deleteAllProgressExcept(accountId: String)
}
