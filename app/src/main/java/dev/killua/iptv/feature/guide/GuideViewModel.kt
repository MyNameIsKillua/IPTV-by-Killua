package dev.killua.iptv.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.WatchlistRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One row of the guide. Only what a row draws and what starting it needs. */
data class GuideChannel(
    val id: String,
    val name: String,
    val logoUrl: String?,
)

data class GuideUiState(
    val channels: List<GuideChannel> = emptyList(),
    /** Keyed by channel id. A channel with no entry yet, or none at all, simply has none. */
    val programmes: Map<String, List<EpgEntry>> = emptyMap(),
    /** Fixed when the screen opens, so the axis does not drift while it is being read. */
    val windowStartEpochSeconds: Long = 0L,
    /** Where the now marker goes. Taken with the window, not per frame; refresh moves both. */
    val nowEpochSeconds: Long = 0L,
    val isLoadingChannels: Boolean = true,
) {
    val windowEndEpochSeconds: Long get() = windowStartEpochSeconds + GUIDE_WINDOW_SECONDS
    val isEmpty: Boolean get() = !isLoadingChannels && channels.isEmpty()
}

/**
 * The guide over the viewer's own channels.
 *
 * The provider answers the programme **one channel at a time**; there is no call that returns many
 * at once. A grid over a six-figure channel list is therefore not a layout problem but a request
 * problem, which is why the rows are the channels the viewer actually keeps: the ones bookmarked
 * onto the saved list, and the ones recently watched. That is tens of rows, not thousands.
 *
 * Requests are capped the same way the channel list caps its own, and the repository's five-minute
 * cache means returning to the guide costs nothing.
 */
class GuideViewModel(
    private val account: Account,
    private val liveRepository: LiveRepository,
    watchlistRepository: WatchlistRepository,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        nowEpochSeconds().let {
            GuideUiState(windowStartEpochSeconds = guideWindowStart(it), nowEpochSeconds = it)
        },
    )
    val state: StateFlow<GuideUiState> = mutableState.asStateFlow()

    private val programmeRequests = Semaphore(MAX_CONCURRENT_REQUESTS)
    private val requested = mutableSetOf<String>()
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                watchlistRepository.observe(account.id, ROW_LIMIT),
                liveRepository.observeRecent(account.id, ROW_LIMIT),
            ) { saved, recent ->
                // Saved first: a bookmark is a deliberate choice, watching once is not.
                val savedChannels = saved
                    .filter { it.kind == WatchlistKind.Channel }
                    .map { GuideChannel(it.contentId, it.title, it.artworkUrl) }
                val recentChannels = recent.map { GuideChannel(it.id, it.name, it.logoUrl) }
                (savedChannels + recentChannels)
                    .distinctBy { it.id }
                    .take(ROW_LIMIT)
            }.collect { channels ->
                mutableState.update { it.copy(channels = channels, isLoadingChannels = false) }
                loadProgrammes(channels)
            }
        }
    }

    /**
     * Asks for every row's programme, a few at a time.
     *
     * A channel already asked for in this session is skipped: the repository would answer from its
     * cache anyway, but not re-entering the queue keeps a changing row list from re-queueing
     * everything each time it changes.
     */
    private fun loadProgrammes(channels: List<GuideChannel>) {
        val pending = channels.map { it.id }.filter { requested.add(it) }
        if (pending.isEmpty()) return
        loadJob = viewModelScope.launch {
            pending.forEach { channelId ->
                launch {
                    programmeRequests.withPermit {
                        val entries = try {
                            liveRepository.epg(account.id, channelId)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            // A guide is decoration around the channels; a provider that cannot
                            // answer for one of them leaves that row blank rather than failing.
                            emptyList()
                        }
                        if (entries.isEmpty()) return@withPermit
                        mutableState.update {
                            it.copy(programmes = it.programmes + (channelId to entries))
                        }
                    }
                }
            }
        }
    }

    /** Re-reads the window and asks again, for a guide left open past its own horizon. */
    fun refresh() {
        loadJob?.cancel()
        requested.clear()
        val now = nowEpochSeconds()
        mutableState.update {
            it.copy(
                windowStartEpochSeconds = guideWindowStart(now),
                nowEpochSeconds = now,
                programmes = emptyMap(),
            )
        }
        loadProgrammes(mutableState.value.channels)
    }

    private companion object {
        /** Tens of rows is the point of this design; a cap keeps a pathological account honest. */
        const val ROW_LIMIT = 40
        const val MAX_CONCURRENT_REQUESTS = 4
    }
}
