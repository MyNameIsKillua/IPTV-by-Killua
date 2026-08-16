package dev.killua.iptv.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.WatchlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MovieDetailsUiState(
    val movieId: String,
    val summary: MovieSummary? = null,
    val details: MovieDetails? = null,
    val isFavorite: Boolean = false,
    /** On the one cross-library saved list, which is separate from the per-library favorite. */
    val isSaved: Boolean = false,
    val progress: WatchProgress? = null,
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
) {
    /** Every descriptive field is optional, so the screen must render from whatever exists. */
    val title: String
        get() = details?.name ?: summary?.name ?: "Movie"

    val canResume: Boolean
        get() = progress?.let { !it.completed && it.positionMs > 0L } == true

    val isWatched: Boolean
        get() = progress?.completed == true
}

class MovieDetailsViewModel(
    private val account: Account,
    private val movieId: String,
    private val repository: MovieRepository,
    private val watchlist: WatchlistRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MovieDetailsUiState(movieId))
    val state: StateFlow<MovieDetailsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeIsFavorite(account.id, movieId).collect { favorite ->
                mutableState.update { it.copy(isFavorite = favorite) }
            }
        }
        viewModelScope.launch {
            watchlist.observeIsSaved(account.id, WatchlistKind.Movie, movieId).collect { saved ->
                mutableState.update { it.copy(isSaved = saved) }
            }
        }
        viewModelScope.launch {
            // Observed rather than read once: the player writes a position while this screen sits
            // in the back stack, and returning to a stale Resume button would be a lie.
            repository.observeProgress(account.id, movieId).collect { progress ->
                mutableState.update { it.copy(progress = progress) }
            }
        }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(isLoading = true, errorMessage = null) }
            // The cached summary is shown even if the detail fetch later fails, so a temporary
            // provider outage still leaves a usable screen instead of an error page.
            val summary = runCatching { repository.getMovie(account.id, movieId) }.getOrNull()
            mutableState.update { it.copy(summary = summary) }
            try {
                val details = repository.details(account.id, movieId)
                mutableState.update { it.copy(details = details, isLoading = false) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showError(failure.failure)
            } catch (_: Exception) {
                showError(AppFailure(FailureKind.Unknown))
            }
        }
    }

    /**
     * The state is not updated here: `observeProgress` already reports the write, so setting it
     * locally as well would make the button flicker between the two sources.
     */
    fun toggleWatched() {
        viewModelScope.launch {
            val target = !mutableState.value.isWatched
            try {
                repository.setWatched(account.id, movieId, target)
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
                repository.setFavorite(account.id, movieId, target)
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
                watchlist.setSaved(account.id, WatchlistKind.Movie, movieId, target)
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
