package dev.killua.iptv.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Reads the rows a provider cannot give back, for the export in Settings.
 *
 * Deliberately its own DAO rather than five methods spread over four existing ones: everything an
 * export can see is then in one file, and reviewing whether it can reach something it should not is
 * reading twenty lines instead of hunting.
 *
 * The library tables are not here at all - categories, titles and episodes are re-downloadable,
 * and an export is not a backup of the cache.
 *
 * The upserts exist for import. They replace rather than ignore, because by the time a row
 * reaches here the merge has already decided it should win. Nothing here deletes: an import can
 * only add a row or move one forward.
 */
@Dao
interface UserDataDao {
    @Query("SELECT * FROM watch_progress WHERE accountId = :accountId")
    suspend fun watchProgress(accountId: String): List<WatchProgressEntity>

    /**
     * Which series each cached episode belongs to.
     *
     * For the export, and for nothing else. An episode's progress row knows only its own provider
     * id, and a client reading the file elsewhere has no way to turn that into a title — the phone
     * does, because it cached the episode list when the series was opened.
     */
    @Query("SELECT remoteEpisodeId, remoteSeriesId FROM series_episodes WHERE accountId = :accountId")
    suspend fun episodeSeriesIds(accountId: String): List<EpisodeSeries>

    @Query("SELECT * FROM movie_favorites WHERE accountId = :accountId")
    suspend fun movieFavorites(accountId: String): List<MovieFavoriteEntity>

    @Query("SELECT * FROM series_favorites WHERE accountId = :accountId")
    suspend fun seriesFavorites(accountId: String): List<SeriesFavoriteEntity>

    @Query("SELECT * FROM watchlist WHERE accountId = :accountId")
    suspend fun watchlist(accountId: String): List<WatchlistEntity>

    @Query("SELECT * FROM recent_channels WHERE accountId = :accountId")
    suspend fun recentChannels(accountId: String): List<RecentChannelEntity>

    @Upsert
    suspend fun upsertWatchProgress(rows: List<WatchProgressEntity>)

    @Upsert
    suspend fun upsertMovieFavorites(rows: List<MovieFavoriteEntity>)

    @Upsert
    suspend fun upsertSeriesFavorites(rows: List<SeriesFavoriteEntity>)

    @Upsert
    suspend fun upsertWatchlist(rows: List<WatchlistEntity>)

    @Upsert
    suspend fun upsertRecentChannels(rows: List<RecentChannelEntity>)
}

/** One row of the episode-to-series lookup the export needs. */
data class EpisodeSeries(val remoteEpisodeId: String, val remoteSeriesId: String)
