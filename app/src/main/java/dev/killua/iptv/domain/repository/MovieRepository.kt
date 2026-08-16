package dev.killua.iptv.domain.repository

import androidx.paging.PagingData
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow

data class MovieSyncResult(
    val categoryCount: Int,
    val movieCount: Int,
    val finishedAtEpochMillis: Long,
)

interface MovieRepository {
    /** Newest first by the provider's `added` timestamp; titles without one are left out. */
    fun observeRecentlyAdded(accountId: String, limit: Int): Flow<List<RecentlyAddedEntry>>

    /** Global search over cached titles, bounded by [limit]. */
    suspend fun search(accountId: String, term: String, limit: Int): SearchSection<MovieSummary>

    fun observeCategories(accountId: String): Flow<List<MovieCategory>>

    /** Heuristic languages actually present in the cached library, for the language filter. */
    fun observeLanguages(accountId: String): Flow<List<String>>

    fun movies(accountId: String, filter: MovieFilter): Flow<PagingData<MovieSummary>>

    fun observeContinueWatching(
        accountId: String,
        limit: Int = 12,
    ): Flow<List<ContinueWatchingEntry>>

    fun observeIsFavorite(accountId: String, movieId: String): Flow<Boolean>

    suspend fun getMovie(accountId: String, movieId: String): MovieSummary?

    /** Reads cached details, fetching and caching them on first use or when [forceRefresh]. */
    suspend fun details(
        accountId: String,
        movieId: String,
        forceRefresh: Boolean = false,
    ): MovieDetails

    suspend fun setFavorite(accountId: String, movieId: String, favorite: Boolean)

    /** True once a sync has stored at least one title, used to decide whether to show sync UI. */
    suspend fun hasCachedLibrary(accountId: String): Boolean

    /**
     * [onProgress] reports the running title count after each written batch, so a long first
     * sync can show real progress instead of an indeterminate spinner.
     */
    suspend fun refresh(accountId: String, onProgress: (Int) -> Unit = {}): MovieSyncResult

    suspend fun progress(accountId: String, movieId: String): WatchProgress?

    /** Observed so a details screen picks up a position written while it was in the back stack. */
    fun observeProgress(accountId: String, movieId: String): Flow<WatchProgress?>

    suspend fun saveProgress(
        accountId: String,
        movieId: String,
        positionMs: Long,
        durationMs: Long,
    )

    /**
     * Marks a Movie watched or unwatched by hand, without playing it.
     *
     * Completion is otherwise only ever derived from playback, which cannot express "I already saw
     * this elsewhere" — the common case on a library this size.
     *
     * Unwatched **removes** the stored position rather than clearing a flag: the mark and a resume
     * point three minutes from the end would contradict each other, and unwatched has to mean the
     * title starts from the beginning again.
     */
    suspend fun setWatched(accountId: String, movieId: String, watched: Boolean)
}
