package dev.killua.iptv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.WatchlistEntry
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.WatchlistRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(
    val account: Account,
    liveRepository: LiveRepository,
    movieRepository: MovieRepository,
    seriesRepository: SeriesRepository,
    watchlistRepository: WatchlistRepository,
) : ViewModel() {
    /**
     * The one saved list, already mixed and newest-first by the query itself.
     *
     * Unlike Continue Watching this needs no merging here: a single statement spans all three
     * libraries, so there is nothing to re-sort.
     */
    val watchlist: StateFlow<List<WatchlistEntry>> = watchlistRepository
        .observe(account.id, ROW_LIMIT)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentChannels: StateFlow<List<LiveChannel>> = liveRepository
        .observeRecent(account.id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Everything with a stored position, from both libraries. Home is where a viewer looks first,
     * so an unfinished film or episode has to be reachable from here rather than two taps away.
     *
     * The two queries are merged and re-sorted by when each title was last watched, then trimmed:
     * showing the first library's twelve and then the second's would put a film from last month
     * ahead of the episode watched this morning.
     */
    val continueWatching: StateFlow<List<ContinueWatchingEntry>> = combine(
        movieRepository.observeContinueWatching(account.id, ROW_LIMIT),
        seriesRepository.observeContinueWatching(account.id, ROW_LIMIT),
    ) { movies, series ->
        (movies + series)
            .sortedByDescending { it.lastWatchedAtEpochMillis }
            .take(ROW_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Newly added films and series, mixed and ordered by the provider's own timestamps — when those
     * timestamps say anything. See [RecentlyAdded] for what "say anything" means and why it is
     * checked at all.
     */
    val recentlyAdded: StateFlow<List<RecentlyAddedEntry>> = combine(
        movieRepository.observeRecentlyAdded(account.id, CANDIDATE_LIMIT),
        seriesRepository.observeRecentlyAdded(account.id, CANDIDATE_LIMIT),
    ) { movies, series ->
        RecentlyAdded.rowOf(movies = movies, series = series, limit = ROW_LIMIT)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val ROW_LIMIT = 12

        /** Wider than the row, so one genuine import batch is less likely to look like the
         * whole library to [RecentlyAdded]'s check. */
        const val CANDIDATE_LIMIT = ROW_LIMIT * RecentlyAdded.CANDIDATE_FACTOR
    }
}
