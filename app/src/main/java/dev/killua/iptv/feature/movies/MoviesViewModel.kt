package dev.killua.iptv.feature.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSortOrder
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.repository.MovieRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MoviesUiState(
    val categories: List<MovieCategory> = emptyList(),
    /** Heuristic languages actually present in the cache; empty means the filter is hidden. */
    val languages: List<String> = emptyList(),
    val filter: MovieFilter = MovieFilter(),
    /** Raw field text, updated per keystroke; the filter only follows after debouncing. */
    val searchInput: String = "",
    val continueWatching: List<ContinueWatchingEntry> = emptyList(),
    val isRefreshing: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val lastUpdatedAtEpochMillis: Long? = null,
    val errorMessage: String? = null,
) {
    val hasActiveFilter: Boolean
        get() = filter.categoryId != null ||
            filter.languageTag != null ||
            filter.favoritesOnly ||
            filter.inProgressOnly ||
            !filter.searchQuery.isNullOrBlank()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MoviesViewModel(
    private val account: Account,
    private val repository: MovieRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(MoviesUiState())
    val state: StateFlow<MoviesUiState> = mutableState.asStateFlow()

    private val filter = MutableStateFlow(MovieFilter())
    private val searchInput = MutableStateFlow("")

    /**
     * Paging is driven by the committed filter rather than by keystrokes, so typing does not
     * rebuild the paging source on every character.
     */
    // StateFlow already conflates equal values, so an unchanged filter never re-pages.
    val movies: Flow<PagingData<MovieSummary>> = filter
        .flatMapLatest { repository.movies(account.id, it) }
        .cachedIn(viewModelScope)

    private var autoRefreshed = false

    init {
        viewModelScope.launch {
            repository.observeCategories(account.id).collect { categories ->
                mutableState.update {
                    it.copy(categories = categories, hasLoadedOnce = categories.isNotEmpty())
                }
                // Mirrors Live: the first unsynced visit fetches once, later visits reuse cache.
                if (!autoRefreshed && categories.isEmpty()) {
                    autoRefreshed = true
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            repository.observeLanguages(account.id).collect { languages ->
                // Drop a language filter that the refreshed library no longer offers, otherwise
                // the grid would stay empty with no visible reason.
                val active = filter.value.languageTag
                if (active != null && active !in languages) {
                    updateFilter { it.copy(languageTag = null) }
                }
                mutableState.update { it.copy(languages = languages) }
            }
        }
        viewModelScope.launch {
            repository.observeContinueWatching(account.id).collect { entries ->
                mutableState.update { it.copy(continueWatching = entries) }
            }
        }
        viewModelScope.launch {
            searchInput
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { query ->
                    // Blank means "no search", so it must clear the filter rather than become an
                    // empty term that still counts as an active filter.
                    val trimmed = query.trim().takeIf(String::isNotEmpty)
                    updateFilter { it.copy(searchQuery = trimmed) }
                }
        }
    }

    fun onSearchInput(value: String) {
        searchInput.value = value
        mutableState.update { it.copy(searchInput = value) }
    }

    fun clearSearch() {
        searchInput.value = ""
        mutableState.update { it.copy(searchInput = "") }
        updateFilter { it.copy(searchQuery = null) }
    }

    fun selectCategory(categoryId: String?) = updateFilter { it.copy(categoryId = categoryId) }

    fun selectLanguage(languageTag: String?) = updateFilter { it.copy(languageTag = languageTag) }

    fun setSortOrder(sortOrder: MovieSortOrder) = updateFilter { it.copy(sortOrder = sortOrder) }

    fun toggleFavoritesOnly() = updateFilter { it.copy(favoritesOnly = !it.favoritesOnly) }

    fun toggleInProgressOnly() = updateFilter { it.copy(inProgressOnly = !it.inProgressOnly) }

    fun clearFilters() {
        searchInput.value = ""
        mutableState.update { it.copy(searchInput = "") }
        updateFilter { MovieFilter(sortOrder = it.sortOrder) }
    }

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true, errorMessage = null) }
            try {
                val result = repository.refresh(account.id)
                mutableState.update {
                    it.copy(
                        isRefreshing = false,
                        hasLoadedOnce = true,
                        lastUpdatedAtEpochMillis = result.finishedAtEpochMillis,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showError(failure.failure)
            } catch (_: OutOfMemoryError) {
                // A provider listing large enough to exhaust the heap must degrade to a safe
                // message; an Error would otherwise escape the coroutine and kill the process.
                showError(AppFailure(FailureKind.LibraryTooLarge))
            } catch (_: Exception) {
                showError(AppFailure(FailureKind.Unknown))
            }
        }
    }

    fun dismissError() = mutableState.update { it.copy(errorMessage = null) }

    private fun updateFilter(transform: (MovieFilter) -> MovieFilter) {
        val updated = transform(filter.value)
        filter.value = updated
        mutableState.update { it.copy(filter = updated, errorMessage = null) }
    }

    private fun showError(failure: AppFailure) {
        mutableState.update {
            it.copy(isRefreshing = false, errorMessage = failure.userMessage())
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
