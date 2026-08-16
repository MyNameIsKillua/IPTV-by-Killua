package dev.killua.iptv.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveDao {
    @Upsert
    suspend fun upsertCategories(categories: List<LiveCategoryEntity>)

    @Upsert
    suspend fun upsertChannels(channels: List<LiveChannelEntity>)

    @Query(
        """
        SELECT * FROM live_categories
        WHERE accountId = :accountId
        ORDER BY sortOrder ASC, name COLLATE NOCASE ASC
        """,
    )
    fun observeCategories(accountId: String): Flow<List<LiveCategoryEntity>>

    /**
     * Distinct heuristic languages present in the cached library, so the filter only ever offers
     * values that would actually match something.
     */
    @Query(
        """
        SELECT DISTINCT languageTag FROM live_channels
        WHERE accountId = :accountId AND languageTag IS NOT NULL
        ORDER BY languageTag ASC
        """,
    )
    fun observeLanguages(accountId: String): Flow<List<String>>

    /**
     * Paged channel browsing. The statement is assembled by `LiveQueryFactory` because the
     * selection, search, and sort combinations would otherwise need one declared query each. All
     * caller-supplied values are bound as arguments; only fixed, enum-derived fragments are ever
     * concatenated.
     *
     * `live_categories` is observed because the uncategorized selection reads it, so a refresh
     * that adds or removes a category re-pages the list.
     */
    @RawQuery(
        observedEntities = [
            LiveChannelEntity::class,
            LiveCategoryEntity::class,
            RecentChannelEntity::class,
        ],
    )
    fun pageChannels(query: SupportSQLiteQuery): PagingSource<Int, LiveChannelEntity>

    @Query(
        """
        SELECT c.* FROM live_channels c
        INNER JOIN recent_channels r
            ON c.accountId = r.accountId AND c.remoteStreamId = r.remoteStreamId
        WHERE c.accountId = :accountId
        ORDER BY r.lastWatchedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(accountId: String, limit: Int): Flow<List<LiveChannelEntity>>


    /**
     * Global search over the cached channel names.
     *
     * One extra row beyond [limit] is fetched so the caller can tell "exactly this many" from
     * "there are more", without paying for a second COUNT scan over a six-figure table.
     */
    @Query(
        """
        SELECT * FROM live_channels
        WHERE accountId = :accountId AND sortName LIKE :pattern ESCAPE '\'
        ORDER BY sortName ASC, remoteStreamId ASC
        LIMIT :limit
        """,
    )
    suspend fun searchChannels(
        accountId: String,
        pattern: String,
        limit: Int,
    ): List<LiveChannelEntity>

    @Query(
        """
        SELECT * FROM live_channels
        WHERE accountId = :accountId AND remoteStreamId = :streamId
        LIMIT 1
        """,
    )
    suspend fun getChannel(accountId: String, streamId: String): LiveChannelEntity?

    @Upsert
    suspend fun upsertRecent(recent: RecentChannelEntity)

    @Query(
        """
        SELECT lastWatchedAtEpochMillis FROM recent_channels
        WHERE accountId = :accountId AND remoteStreamId = :streamId
        LIMIT 1
        """,
    )
    suspend fun getLastWatched(accountId: String, streamId: String): Long?

    @Query("SELECT COUNT(*) FROM live_channels WHERE accountId = :accountId")
    suspend fun countChannels(accountId: String): Int

    @Query("DELETE FROM live_categories WHERE accountId = :accountId AND syncGeneration != :generation")
    suspend fun deleteStaleCategories(accountId: String, generation: Long)

    @Query("DELETE FROM live_channels WHERE accountId = :accountId AND syncGeneration != :generation")
    suspend fun deleteStaleChannels(accountId: String, generation: Long)

    @Query("DELETE FROM live_categories WHERE accountId = :accountId")
    suspend fun deleteCategoriesForAccount(accountId: String)

    @Query("DELETE FROM live_channels WHERE accountId = :accountId")
    suspend fun deleteChannelsForAccount(accountId: String)

    @Query("DELETE FROM recent_channels WHERE accountId = :accountId")
    suspend fun deleteRecentsForAccount(accountId: String)

    @Query("DELETE FROM recent_channels")
    suspend fun deleteAllRecents()

    @Query("DELETE FROM live_channels")
    suspend fun deleteAllChannels()

    @Query("DELETE FROM live_categories")
    suspend fun deleteAllCategories()

    @Query("DELETE FROM recent_channels WHERE accountId != :accountId")
    suspend fun deleteAllRecentsExcept(accountId: String)

    @Query("DELETE FROM live_channels WHERE accountId != :accountId")
    suspend fun deleteAllChannelsExcept(accountId: String)

    @Query("DELETE FROM live_categories WHERE accountId != :accountId")
    suspend fun deleteAllCategoriesExcept(accountId: String)
}
