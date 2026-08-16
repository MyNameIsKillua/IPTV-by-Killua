package dev.killua.iptv.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The saved list, read as one thing across all three libraries.
 *
 * The read is a `UNION ALL` of three joins rather than three separate queries merged in Kotlin,
 * because ordering by when something was saved has to hold *across* the libraries — merging
 * afterwards would mean holding and re-sorting three lists to answer one question.
 *
 * Each branch is an `INNER JOIN` onto its library table, so a title the provider has dropped stops
 * being shown without its saved row being deleted. It comes back if the provider does.
 */
@Dao
interface WatchlistDao {
    @Upsert
    suspend fun upsert(entry: WatchlistEntity)

    @Query(
        """
        DELETE FROM watchlist
        WHERE accountId = :accountId AND contentType = :contentType AND contentId = :contentId
        """,
    )
    suspend fun delete(accountId: String, contentType: String, contentId: String)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM watchlist
            WHERE accountId = :accountId AND contentType = :contentType AND contentId = :contentId
        )
        """,
    )
    fun observeIsSaved(accountId: String, contentType: String, contentId: String): Flow<Boolean>

    /**
     * Every saved id of one kind, for a list that has to mark many rows at once.
     *
     * A paged channel list cannot ask [observeIsSaved] per row — that would be one query per visible
     * row on every scroll. Saved ids are few by nature, so the whole set is cheap to hold.
     */
    @Query(
        """
        SELECT contentId FROM watchlist
        WHERE accountId = :accountId AND contentType = :contentType
        """,
    )
    fun observeSavedIds(accountId: String, contentType: String): Flow<List<String>>

    @Query(
        """
        SELECT w.contentType AS contentType,
               w.contentId AS contentId,
               m.name AS name,
               m.posterUrl AS artworkUrl,
               w.addedAtEpochMillis AS addedAtEpochMillis
        FROM watchlist w
        INNER JOIN movies m
            ON m.accountId = w.accountId AND m.remoteStreamId = w.contentId
        WHERE w.accountId = :accountId AND w.contentType = :movieType

        UNION ALL

        SELECT w.contentType, w.contentId, s.name, s.posterUrl, w.addedAtEpochMillis
        FROM watchlist w
        INNER JOIN series s
            ON s.accountId = w.accountId AND s.remoteSeriesId = w.contentId
        WHERE w.accountId = :accountId AND w.contentType = :seriesType

        UNION ALL

        SELECT w.contentType, w.contentId, c.name, c.logoUrl, w.addedAtEpochMillis
        FROM watchlist w
        INNER JOIN live_channels c
            ON c.accountId = w.accountId AND c.remoteStreamId = w.contentId
        WHERE w.accountId = :accountId AND w.contentType = :channelType

        ORDER BY addedAtEpochMillis DESC
        LIMIT :limit
        """,
    )
    fun observeSaved(
        accountId: String,
        movieType: String,
        seriesType: String,
        channelType: String,
        limit: Int,
    ): Flow<List<WatchlistProjection>>

    @Query("DELETE FROM watchlist")
    suspend fun deleteAll()

    @Query("DELETE FROM watchlist WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}

/** Projected rather than returning entities, because name and artwork live on the library table. */
data class WatchlistProjection(
    val contentType: String,
    val contentId: String,
    val name: String,
    val artworkUrl: String?,
    val addedAtEpochMillis: Long,
)
