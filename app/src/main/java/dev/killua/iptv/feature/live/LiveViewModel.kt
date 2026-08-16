package dev.killua.iptv.feature.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.epg.EpgSelection
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.CategorySelection
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveFilter
import dev.killua.iptv.domain.model.LiveSortOrder
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.WatchlistRepository
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch

data class LiveUiState(
    val account: Account,
    val categories: List<LiveCategory> = emptyList(),
    /** Heuristic languages actually present in the cache; empty means the filter is hidden. */
    val languages: List<String> = emptyList(),
    val filter: LiveFilter = LiveFilter(),
    /** Raw field text, updated per keystroke; the filter only follows after debouncing. */
    val searchInput: String = "",
    val isRefreshing: Boolean = false,
    val hasLoadedOnce: Boolean = account.lastLiveSyncAtEpochMillis != null,
    val lastUpdatedAtEpochMillis: Long? = account.lastLiveSyncAtEpochMillis,
    /** What is on now per channel, filled in only for rows the viewer actually stopped on. */
    val nowPlaying: Map<String, EpgEntry> = emptyMap(),
    /** Held as a whole set: a paged list cannot ask about each visible row separately. */
    val savedChannelIds: Set<String> = emptySet(),
    val errorMessage: String? = null,
) {
    val selection: CategorySelection get() = filter.selection

    /**
     * Whether an empty list could be caused by the current narrowing rather than by an empty
     * cache. The sort order is deliberately excluded: it reorders results, it never removes any.
     */
    val hasActiveFilter: Boolean
        get() = filter.selection != CategorySelection.All ||
            filter.languageTag != null ||
            !filter.searchQuery.isNullOrBlank()
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LiveViewModel(
    private val account: Account,
    private val repository: LiveRepository,
    private val watchlist: WatchlistRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LiveUiState(account))
    val state: StateFlow<LiveUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            watchlist.observeSavedIds(account.id, WatchlistKind.Channel).collect { ids ->
                mutableState.update { it.copy(savedChannelIds = ids) }
            }
        }
    }

    /** The mark follows the store, so a refused write leaves the row as it was. */
    fun toggleSaved(channelId: String) {
        viewModelScope.launch {
            val target = channelId !in mutableState.value.savedChannelIds
            runCatching {
                watchlist.setSaved(account.id, WatchlistKind.Channel, channelId, target)
            }
        }
    }

    private val filter = MutableStateFlow(LiveFilter())
    private val searchInput = MutableStateFlow("")

    /**
     * Paging is driven by the committed filter rather than by keystrokes, so typing does not
     * rebuild the paging source on every character. StateFlow conflates equal values, so an
     * unchanged filter never re-pages.
     */
    val channels: Flow<PagingData<LiveChannel>> = filter
        .flatMapLatest { repository.channels(account.id, it) }
        .cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            repository.observeCategories(account.id).collect { categories ->
                mutableState.update { it.copy(categories = categories) }
            }
        }
        viewModelScope.launch {
            repository.observeLanguages(account.id).collect { languages ->
                // Drop a language filter that the refreshed library no longer offers, otherwise
                // the list would stay empty with no visible reason.
                val active = filter.value.languageTag
                if (active != null && active !in languages) {
                    updateFilter { it.copy(languageTag = null) }
                }
                mutableState.update { it.copy(languages = languages) }
            }
        }
        viewModelScope.launch {
            searchInput
                .debounce(SEARCH_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { query ->
                    // Blank means "no search", so it must clear the term rather than become an
                    // empty one that still counts as an active filter.
                    val trimmed = query.trim().takeIf(String::isNotEmpty)
                    updateFilter { it.copy(searchQuery = trimmed) }
                }
        }
        if (!mutableState.value.hasLoadedOnce) refresh()
    }

    fun select(value: CategorySelection) = updateFilter { it.copy(selection = value) }

    fun onSearchInput(value: String) {
        searchInput.value = value
        mutableState.update { it.copy(searchInput = value) }
    }

    fun clearSearch() {
        searchInput.value = ""
        mutableState.update { it.copy(searchInput = "") }
        updateFilter { it.copy(searchQuery = null) }
    }

    fun selectLanguage(languageTag: String?) = updateFilter { it.copy(languageTag = languageTag) }

    fun setSortOrder(sortOrder: LiveSortOrder) = updateFilter { it.copy(sortOrder = sortOrder) }

    /** Returns to the unfiltered list while keeping the chosen order. */
    fun clearFilters() {
        searchInput.value = ""
        mutableState.update { it.copy(searchInput = "") }
        updateFilter { LiveFilter(sortOrder = it.sortOrder) }
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
                // Same guard as Movies: an Error would escape the coroutine and kill the process.
                showError(AppFailure(FailureKind.LibraryTooLarge))
            } catch (_: Exception) {
                showError(AppFailure(FailureKind.Unknown))
            }
        }
    }

    private fun updateFilter(transform: (LiveFilter) -> LiveFilter) {
        val updated = transform(filter.value)
        filter.value = updated
        mutableState.update { it.copy(filter = updated, errorMessage = null) }
    }

    private fun showError(failure: AppFailure) {
        mutableState.update {
            it.copy(isRefreshing = false, errorMessage = failure.userMessage())
        }
    }

    /**
     * Guide requests for channels the viewer has settled on.
     *
     * A row asks for its programme only after it has stayed on screen, and the request is capped
     * so a slow scroll cannot open a connection per row. Without both, scrolling a six-figure
     * channel list would turn into a request storm against the provider — which is why the guide
     * did not simply ship with the list in the first place.
     */
    fun requestEpg(streamId: String) {
        if (!requestedEpg.add(streamId)) return
        viewModelScope.launch {
            val entry = epgRequests.withPermit {
                val entries = repository.epg(account.id, streamId)
                EpgSelection.nowPlaying(entries, System.currentTimeMillis() / 1_000L)
            }
            if (entry == null) return@launch
            mutableState.update { it.copy(nowPlaying = it.nowPlaying + (streamId to entry)) }
        }
    }

    /** Channels already asked for, so a row returning to screen does not ask twice. */
    private val requestedEpg = mutableSetOf<String>()
    private val epgRequests = Semaphore(MAX_CONCURRENT_EPG_REQUESTS)

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L

        /** Enough to fill a screen quickly, few enough not to look like a burst to a provider. */
        const val MAX_CONCURRENT_EPG_REQUESTS = 4
    }
}
