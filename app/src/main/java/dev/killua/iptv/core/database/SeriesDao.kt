package dev.killua.iptv.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Upsert
    suspend fun upsertCategories(categories: List<SeriesCategoryEntity>)

    @Upsert
    suspend fun upsertSeries(series: List<SeriesEntity>)

    @Upsert
    suspend fun upsertDetails(details: SeriesDetailsEntity)

    @Upsert
    suspend fun upsertEpisodes(episodes: List<SeriesEpisodeEntity>)

    /**
     * Episodes share `watch_progress` with Movies, distinguished only by `contentType`. The rows
     * are declared here as well as on `MovieDao` because a DAO is a query holder, not an owner;
     * the table itself is cleared for every content type by the Movie repository's cleaner.
     */
    @Upsert
    suspend fun upsertProgress(progress: WatchProgressEntity)

    /**
     * Removes one episode's stored position, which is how "mark as unwatched" is expressed. Same
     * statement as `MovieDao.deleteProgress`, scoped by `contentType` like every other query here.
     */
    @Query(
        """
        DELETE FROM watch_progress
        WHERE accountId = :accountId AND contentType = :contentType AND contentId = :contentId
        """,
    )
    suspend fun deleteProgress(accountId: String, contentType: String, contentId: String)

    @Upsert
    suspend fun upsertFavorite(favorite: SeriesFavoriteEntity)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM series_favorites
            WHERE accountId = :accountId AND remoteSeriesId = :seriesId
        )
        """,
    )
    fun observeIsFavorite(accountId: String, seriesId: String): Flow<Boolean>

    @Query(
        "DELETE FROM series_favorites WHERE accountId = :accountId AND remoteSeriesId = :seriesId",
    )
    suspend fun deleteFavorite(accountId: String, seriesId: String)

    /**
     * Continue Watching for Series.
     *
     * A series is unfinished when any of its episodes is, so the rows are grouped and ordered by
     * the most recent episode position rather than by the series itself. Joining through
     * `series_episodes` also means a series the provider removed drops out of the row without its
     * episode positions being deleted.
     */
    @Query(
        """
        SELECT s.remoteSeriesId AS remoteId,
               s.name AS name,
               s.posterUrl AS posterUrl,
               MAX(p.updatedAtEpochMillis) AS lastWatchedAtEpochMillis
        FROM series s
        INNER JOIN series_episodes e
            ON e.accountId = s.accountId AND e.remoteSeriesId = s.remoteSeriesId
        INNER JOIN watch_progress p
            ON p.accountId = e.accountId AND p.contentId = e.remoteEpisodeId
        WHERE s.accountId = :accountId
          AND p.contentType = :contentType
          AND p.completed = 0
          AND p.positionMs > 0
        GROUP BY s.remoteSeriesId
        ORDER BY MAX(p.updatedAtEpochMillis) DESC
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
        SELECT * FROM series_categories
        WHERE accountId = :accountId
        ORDER BY sortOrder ASC, name COLLATE NOCASE ASC
        """,
    )
    fun observeCategories(accountId: String): Flow<List<SeriesCategoryEntity>>

    /** Distinct heuristic languages present in the cache, so the filter only offers real values. */
    @Query(
        """
        SELECT DISTINCT languageTag FROM series
        WHERE accountId = :accountId AND languageTag IS NOT NULL
        ORDER BY languageTag ASC
        """,
    )
    fun observeLanguages(accountId: String): Flow<List<String>>

    /**
     * Paged Series browsing, assembled by `SeriesQueryFactory` for the same reason Live and Movies
     * are: one declared query per filter and sort combination would be dozens of near-identical
     * methods. All caller values are bound; only enum-derived fragments are concatenated.
     */
    @RawQuery(observedEntities = [SeriesEntity::class])
    fun pageSeries(query: SupportSQLiteQuery): PagingSource<Int, SeriesEntity>

    /**
     * Newest first by the provider's `last_modified`, which is indexed.
     *
     * For a series that is the closest thing to "new": providers bump it when an episode arrives,
     * so a returning show surfaces as well as a brand new one. Rows without one are left out.
     */
    @Query(
        """
        SELECT * FROM series
        WHERE accountId = :accountId AND lastModifiedEpochSeconds IS NOT NULL
        ORDER BY lastModifiedEpochSeconds DESC
        LIMIT :limit
        """,
    )
    fun observeRecentlyAdded(accountId: String, limit: Int): Flow<List<SeriesEntity>>

    /** Global search; see [LiveDao.searchChannels] for why one row beyond the limit is read. */
    @Query(
        """
        SELECT * FROM series
        WHERE accountId = :accountId AND sortName LIKE :pattern ESCAPE '\'
        ORDER BY sortName ASC, remoteSeriesId ASC
        LIMIT :limit
        """,
    )
    suspend fun searchSeries(accountId: String, pattern: String, limit: Int): List<SeriesEntity>

    @Query(
        """
        SELECT * FROM series
        WHERE accountId = :accountId AND remoteSeriesId = :seriesId
        LIMIT 1
        """,
    )
    suspend fun getSeries(accountId: String, seriesId: String): SeriesEntity?

    @Query(
        """
        SELECT * FROM series_details
        WHERE accountId = :accountId AND remoteSeriesId = :seriesId
        LIMIT 1
        """,
    )
    suspend fun getDetails(accountId: String, seriesId: String): SeriesDetailsEntity?

    @Query(
        """
        SELECT * FROM series_episodes
        WHERE accountId = :accountId AND remoteSeriesId = :seriesId
        ORDER BY seasonNumber ASC, episodeNumber IS NULL, episodeNumber ASC, remoteEpisodeId ASC
        """,
    )
    suspend fun getEpisodes(accountId: String, seriesId: String): List<SeriesEpisodeEntity>

    @Query(
        """
        SELECT * FROM series_episodes
        WHERE accountId = :accountId AND remoteEpisodeId = :episodeId
        LIMIT 1
        """,
    )
    suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisodeEntity?

    @Query(
        """
        SELECT * FROM watch_progress
        WHERE accountId = :accountId AND contentType = :contentType AND contentId = :episodeId
        LIMIT 1
        """,
    )
    suspend fun getEpisodeProgress(
        accountId: String,
        contentType: String,
        episodeId: String,
    ): WatchProgressEntity?

    /**
     * Every stored position within one series, in a single query.
     *
     * Joining against `series_episodes` is what scopes the shared table to this series, and it
     * also means a position whose episode the provider dropped stops being shown without its row
     * being deleted — the same behaviour Movies and live recents have.
     */
    @Query(
        """
        SELECT p.* FROM watch_progress p
        INNER JOIN series_episodes e
            ON e.accountId = p.accountId AND e.remoteEpisodeId = p.contentId
        WHERE p.accountId = :accountId
          AND p.contentType = :contentType
          AND e.remoteSeriesId = :seriesId
        """,
    )
    fun observeProgressForSeries(
        accountId: String,
        contentType: String,
        seriesId: String,
    ): Flow<List<WatchProgressEntity>>

    /**
     * Replaces a series' episodes as a set. Running this inside the same transaction as the insert
     * is what removes episodes the provider dropped, without a sync generation on the table.
     */
    @Query("DELETE FROM series_episodes WHERE accountId = :accountId AND remoteSeriesId = :seriesId")
    suspend fun deleteEpisodesForSeries(accountId: String, seriesId: String)

    @Query("SELECT COUNT(*) FROM series WHERE accountId = :accountId")
    suspend fun countSeries(accountId: String): Int

    @Query("DELETE FROM series WHERE accountId = :accountId AND syncGeneration != :generation")
    suspend fun deleteStaleSeries(accountId: String, generation: Long)

    @Query(
        "DELETE FROM series_categories WHERE accountId = :accountId AND syncGeneration != :generation",
    )
    suspend fun deleteStaleCategories(accountId: String, generation: Long)

    @Query("DELETE FROM series")
    suspend fun deleteAllSeries()

    @Query("DELETE FROM series_categories")
    suspend fun deleteAllCategories()

    @Query("DELETE FROM series_details")
    suspend fun deleteAllDetails()

    @Query("DELETE FROM series_episodes")
    suspend fun deleteAllEpisodes()

    @Query("DELETE FROM series_favorites")
    suspend fun deleteAllFavorites()

    @Query("DELETE FROM series WHERE accountId != :accountId")
    suspend fun deleteAllSeriesExcept(accountId: String)

    @Query("DELETE FROM series_categories WHERE accountId != :accountId")
    suspend fun deleteAllCategoriesExcept(accountId: String)

    @Query("DELETE FROM series_details WHERE accountId != :accountId")
    suspend fun deleteAllDetailsExcept(accountId: String)

    @Query("DELETE FROM series_episodes WHERE accountId != :accountId")
    suspend fun deleteAllEpisodesExcept(accountId: String)

    @Query("DELETE FROM series_favorites WHERE accountId != :accountId")
    suspend fun deleteAllFavoritesExcept(accountId: String)
}
