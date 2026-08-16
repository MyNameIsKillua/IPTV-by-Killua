package dev.killua.iptv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.core.database.LikeSearchTerm
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    /** Raw field text, updated per keystroke; the query only follows after debouncing. */
    val input: String = "",
    /** The term the shown results belong to, so the screen never labels stale results. */
    val submittedTerm: String = "",
    val channels: SearchSection<LiveChannel> = SearchSection(),
    val movies: SearchSection<MovieSummary> = SearchSection(),
    val series: SearchSection<SeriesSummary> = SearchSection(),
    val isSearching: Boolean = false,
    val errorMessage: String? = null,
) {
    /** Measured on the normalized term: `...` is three keystrokes and nothing to search for. */
    val isTermTooShort: Boolean
        get() = input.isNotBlank() &&
            LikeSearchTerm.normalize(input).length < LikeSearchTerm.MINIMUM_GLOBAL_LENGTH

    val hasAnyResult: Boolean
        get() = !channels.isEmpty || !movies.isEmpty || !series.isEmpty

    /** True only once a real search has finished and found nothing, never while typing. */
    val isEmptyResult: Boolean
        get() = submittedTerm.isNotEmpty() && !isSearching && !hasAnyResult && errorMessage == null
}

/**
 * Global search across the three cached libraries.
 *
 * It reads only what is already on the device: the provider is never asked to search, because
 * Xtream has no search endpoint and re-downloading a six-figure listing per keystroke is not a
 * search. Every library is queried concurrently and bounded, so one huge table cannot make the
 * other two wait for it.
 *
 * The debounce matters more here than in a single library: one keystroke costs three scans.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val account: Account,
    private val liveRepository: LiveRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = mutableState.asStateFlow()

    private val input = MutableStateFlow("")

    /** Raised per section by "Show more", and reset whenever the term changes. */
    private val limits = MutableStateFlow(SectionLimits())

    init {
        viewModelScope.launch {
            input
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { term -> runSearch(term.trim()) }
        }
    }

    fun onInput(value: String) {
        input.value = value
        mutableState.update { it.copy(input = value) }
        // A new term invalidates any expansion the previous one was showing.
        limits.value = SectionLimits()
    }

    fun clear() {
        input.value = ""
        limits.value = SectionLimits()
        mutableState.value = SearchUiState()
    }

    fun retry() = runSearchInScope(mutableState.value.input.trim())

    fun showMoreChannels() = expand { it.copy(channels = it.channels + PAGE) }

    fun showMoreMovies() = expand { it.copy(movies = it.movies + PAGE) }

    fun showMoreSeries() = expand { it.copy(series = it.series + PAGE) }

    private fun expand(next: (SectionLimits) -> SectionLimits) {
        limits.value = next(limits.value)
        runSearchInScope(mutableState.value.input.trim())
    }

    private fun runSearchInScope(term: String) {
        viewModelScope.launch { runSearch(term) }
    }

    private suspend fun runSearch(term: String) {
        // The normalized length decides, so punctuation alone never costs three table scans.
        if (LikeSearchTerm.normalize(term).length < LikeSearchTerm.MINIMUM_GLOBAL_LENGTH) {
            // Clear results rather than leaving the previous term's hits under a shorter one.
            mutableState.update {
                it.copy(
                    submittedTerm = "",
                    channels = SearchSection(),
                    movies = SearchSection(),
                    series = SearchSection(),
                    isSearching = false,
                    errorMessage = null,
                )
            }
            return
        }

        mutableState.update { it.copy(isSearching = true, errorMessage = null) }
        val current = limits.value
        try {
            // Concurrent, so the slowest table sets the wait rather than the sum of all three.
            coroutineScope {
                val channels = async { liveRepository.search(account.id, term, current.channels) }
                val movies = async { movieRepository.search(account.id, term, current.movies) }
                val series = async { seriesRepository.search(account.id, term, current.series) }
                val results = Triple(channels.await(), movies.await(), series.await())
                mutableState.update {
                    it.copy(
                        submittedTerm = term,
                        channels = results.first,
                        movies = results.second,
                        series = results.third,
                        isSearching = false,
                    )
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: AppFailureException) {
            showError(failure.failure)
        } catch (_: Exception) {
            showError(AppFailure(FailureKind.Unknown))
        }
    }

    private fun showError(failure: AppFailure) = mutableState.update {
        it.copy(isSearching = false, errorMessage = failure.userMessage())
    }

    private data class SectionLimits(
        val channels: Int = PAGE,
        val movies: Int = PAGE,
        val series: Int = PAGE,
    )

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L

        /** How many hits a section shows, and how much each "Show more" adds. */
        const val PAGE = 20
    }
}
