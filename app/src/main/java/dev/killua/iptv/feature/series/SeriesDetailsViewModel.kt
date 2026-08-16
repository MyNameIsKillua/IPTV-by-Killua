package dev.killua.iptv.feature.series

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.WatchlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeriesDetailsUiState(
    val seriesId: String,
    val summary: SeriesSummary? = null,
    val details: SeriesDetails? = null,
    val selectedSeason: Int? = null,
    val isFavorite: Boolean = false,
    /** On the one cross-library saved list, which is separate from the per-library favorite. */
    val isSaved: Boolean = false,
    /** Stored positions keyed by episode ID; absent means never started. */
    val episodeProgress: Map<String, WatchProgress> = emptyMap(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    /** Every descriptive field is optional, so the screen renders from whatever exists. */
    val title: String
        get() = details?.name ?: summary?.name ?: "Series"

    /** Season numbers in ascending order, taken from the episodes themselves. */
    val seasons: List<Int>
        get() = details?.episodes?.map { it.seasonNumber }?.distinct()?.sorted().orEmpty()

    val episodesOfSelectedSeason: List<SeriesEpisode>
        get() {
            val episodes = details?.episodes.orEmpty()
            val season = selectedSeason ?: return episodes
            return episodes.filter { it.seasonNumber == season }
        }

    /**
     * The episode the primary action starts: the earliest one begun but not finished, otherwise
     * the first unwatched one, and once everything is watched the first episode again.
     */
    val nextEpisode: SeriesEpisode?
        get() {
            val episodes = details?.episodes.orEmpty()
            return episodes.firstOrNull { episode ->
                episodeProgress[episode.id]?.let { !it.completed && it.positionMs > 0L } == true
            }
                ?: episodes.firstOrNull { episodeProgress[it.id]?.completed != true }
                ?: episodes.firstOrNull()
        }

    val nextEpisodeProgress: WatchProgress?
        get() = nextEpisode?.let { episodeProgress[it.id] }

    val canResume: Boolean
        get() = nextEpisodeProgress?.let { !it.completed && it.positionMs > 0L } == true
}

class SeriesDetailsViewModel(
    private val account: Account,
    private val seriesId: String,
    private val repository: SeriesRepository,
    private val watchlist: WatchlistRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SeriesDetailsUiState(seriesId))
    val state: StateFlow<SeriesDetailsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeIsFavorite(account.id, seriesId).collect { favorite ->
                mutableState.update { it.copy(isFavorite = favorite) }
            }
        }
        viewModelScope.launch {
            watchlist.observeIsSaved(account.id, WatchlistKind.Series, seriesId).collect { saved ->
                mutableState.update { it.copy(isSaved = saved) }
            }
        }
        viewModelScope.launch {
            // Observed rather than read once: the player writes positions while this screen sits
            // in the back stack, and returning to stale episode marks would be a lie.
            repository.observeEpisodeProgress(account.id, seriesId).collect { progress ->
                mutableState.update { it.copy(episodeProgress = progress) }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null) }
            // The cached summary is shown even if the detail fetch later fails, so a temporary
            // provider outage still leaves a usable screen instead of an error page.
            val summary = runCatching { repository.getSeries(account.id, seriesId) }.getOrNull()
            mutableState.update { it.copy(summary = summary) }
            try {
                val details = repository.details(account.id, seriesId)
                mutableState.update { current ->
                    val seasons = details.episodes.map { it.seasonNumber }.distinct().sorted()
                    current.copy(
                        details = details,
                        // Keep the viewer's season if it still exists, otherwise open the first.
                        selectedSeason = current.selectedSeason?.takeIf { it in seasons }
                            ?: seasons.firstOrNull(),
                        isLoading = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showError(failure.failure)
            } catch (_: Exception) {
                showError(AppFailure(FailureKind.Unknown))
            }
        }
    }

    fun selectSeason(season: Int) = mutableState.update { it.copy(selectedSeason = season) }

    /**
     * Marks one episode watched or unwatched. The state is not updated here: the observed progress
     * flow reports the write, which is also what moves the primary button on to the next episode.
     */
    fun toggleEpisodeWatched(episodeId: String) {
        viewModelScope.launch {
            val target = mutableState.value.episodeProgress[episodeId]?.completed != true
            try {
                repository.setEpisodeWatched(account.id, episodeId, target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showError(failure.failure)
            } catch (_: Exception) {
                showError(AppFailure(FailureKind.Unknown))
            }
        }
    }

    /** Like [toggleFavorite]: the observed flow reports the write, so nothing is set locally. */
    fun toggleSaved() {
        viewModelScope.launch {
            val target = !mutableState.value.isSaved
            try {
                watchlist.setSaved(account.id, WatchlistKind.Series, seriesId, target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showError(failure.failure)
            } catch (_: Exception) {
                showError(AppFailure(FailureKind.Unknown))
            }
        }
    }

    fun toggleFavorite() {
        viewModelScope.launch {
            val target = !mutableState.value.isFavorite
            try {
                repository.setFavorite(account.id, seriesId, target)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showError(failure.failure)
            } catch (_: Exception) {
                showError(AppFailure(FailureKind.Unknown))
            }
        }
    }

    private fun showError(failure: AppFailure) {
        mutableState.update { it.copy(isLoading = false, errorMessage = failure.userMessage()) }
    }
}
