package dev.killua.iptv.domain.repository

import androidx.paging.PagingData
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow

data class SeriesSyncResult(
    val categoryCount: Int,
    val seriesCount: Int,
    val finishedAtEpochMillis: Long,
)

interface SeriesRepository {
    /** Newest first by the provider's `last_modified`; series without one are left out. */
    fun observeRecentlyAdded(accountId: String, limit: Int): Flow<List<RecentlyAddedEntry>>

    /** Global search over cached series names, bounded by [limit]. */
    suspend fun search(accountId: String, term: String, limit: Int): SearchSection<SeriesSummary>

    fun observeCategories(accountId: String): Flow<List<SeriesCategory>>

    /** Heuristic languages actually present in the cached library, for the language filter. */
    fun observeLanguages(accountId: String): Flow<List<String>>

    fun series(accountId: String, filter: SeriesFilter): Flow<PagingData<SeriesSummary>>

    /** Series with an unfinished episode, ordered by the most recent one. */
    fun observeContinueWatching(
        accountId: String,
        limit: Int = 12,
    ): Flow<List<ContinueWatchingEntry>>

    fun observeIsFavorite(accountId: String, seriesId: String): Flow<Boolean>

    suspend fun setFavorite(accountId: String, seriesId: String, favorite: Boolean)

    suspend fun getSeries(accountId: String, seriesId: String): SeriesSummary?

    /**
     * Reads cached details and episodes, fetching and caching them on first use or when
     * [forceRefresh]. A fetch replaces the series' episodes as a set, so one the provider dropped
     * disappears rather than lingering.
     */
    suspend fun details(
        accountId: String,
        seriesId: String,
        forceRefresh: Boolean = false,
    ): SeriesDetails

    /** A single cached episode, which is what playback will resolve a stream from. */
    suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisode?

    /**
     * The episode that follows [episodeId] within its own series, in the order the details screen
     * lists them. Null at the end of a series, or when the episode is no longer cached.
     */
    suspend fun nextEpisode(accountId: String, episodeId: String): SeriesEpisode?

    /**
     * The episode before [episodeId] in that same order. Null at the very start of a series.
     *
     * Symmetric with [nextEpisode] on purpose: "previous" has to mean the row above the current
     * one, including across a season boundary, or the two controls would disagree about what the
     * list looks like.
     */
    suspend fun previousEpisode(accountId: String, episodeId: String): SeriesEpisode?

    /** True once a sync has stored at least one series, used to decide whether to show sync UI. */
    suspend fun hasCachedLibrary(accountId: String): Boolean

    /** [onProgress] reports the running series count after each written batch. */
    suspend fun refresh(accountId: String, onProgress: (Int) -> Unit = {}): SeriesSyncResult

    /** The stored position of one episode, which is what the player resumes from. */
    suspend fun episodeProgress(accountId: String, episodeId: String): WatchProgress?

    /**
     * Every stored position within one series, keyed by episode ID.
     *
     * Observing the whole series in a single query is what lets a details screen mark each row
     * without one Flow per episode, and what makes a position written by the player appear when
     * the screen comes back off the back stack.
     */
    fun observeEpisodeProgress(
        accountId: String,
        seriesId: String,
    ): Flow<Map<String, WatchProgress>>

    suspend fun saveEpisodeProgress(
        accountId: String,
        episodeId: String,
        positionMs: Long,
        durationMs: Long,
    )

    /**
     * Marks one episode watched or unwatched by hand, mirroring [MovieRepository.setWatched].
     *
     * It is deliberately per episode rather than per series: the series screen already derives what
     * to offer next from the individual episodes, so marking one is what actually moves that
     * forward, and a whole-series toggle would write thousands of rows for a single tap.
     */
    suspend fun setEpisodeWatched(accountId: String, episodeId: String, watched: Boolean)
}
