package dev.killua.iptv.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dev.killua.iptv.core.player.PlaybackCommands
import dev.killua.iptv.core.player.PlaybackMediaId
import dev.killua.iptv.core.player.PlaybackRequest
import dev.killua.iptv.core.player.PlaybackSnapshot
import dev.killua.iptv.core.player.PlaybackStateSource
import dev.killua.iptv.core.player.PlayerPresentationState
import dev.killua.iptv.core.player.WatchProgressWriter
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.displayLabel
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlayerUiState(
    val account: Account,
    val request: PlaybackRequest,
    val title: String? = null,
    val snapshot: PlaybackSnapshot = PlaybackSnapshot(),
    /** Live only, and empty whenever the provider has no guide for the channel. */
    val epg: List<EpgEntry> = emptyList(),
    /** Episodes only: what a Next control would start, and null at the end of a series. */
    val nextEpisode: SeriesEpisode? = null,
    /** Episodes only: what a Previous control would start, and null at the start of a series. */
    val previousEpisode: SeriesEpisode? = null,
    /**
     * Set once, when an episode finishes and autoplay is on. The screen navigates and clears it;
     * a plain flag rather than an event stream, because it must survive a recomposition but must
     * not fire twice.
     */
    val autoAdvanceTo: SeriesEpisode? = null,
    /**
     * Seconds left before autoplay starts the next episode, or null when no countdown is running.
     *
     * The countdown exists so the ending is interruptible: an episode that runs out while the
     * viewer has stopped watching should not silently pull the next one in behind it.
     */
    val autoAdvanceCountdownSeconds: Int? = null,
    val isStarting: Boolean = true,
    val startError: AppFailure? = null,
)

/**
 * Drives one playback session, for a live channel, a Movie, or an episode.
 *
 * All three share the same service-owned player and the same screen; only how the item is resolved
 * and what a confirmed playback means differ. Live marks a channel recent after two seconds; the
 * resumable types checkpoint their position so they can be continued.
 */
class PlayerViewModel(
    private val account: Account,
    private val request: PlaybackRequest,
    private val liveRepository: LiveRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    private val coordinator: PlaybackCommands,
    private val connection: PlaybackStateSource,
    private val presentationState: PlayerPresentationState,
    private val progressWriter: WatchProgressWriter,
    /** Read once when the screen opens; changing it mid-episode would be a surprise. */
    private val autoPlayNextEpisode: Boolean = true,
) : ViewModel() {
    /**
     * Identifies this screen to [PlayerPresentationState]. An episode change replaces the player, and
     * the outgoing screen is torn down after the incoming one is running; without this its late
     * `hide` would switch Picture-in-Picture off for the episode that just started.
     */
    private val presentationOwner = Any()
    private val mediaId: PlaybackMediaId = when (request) {
        is PlaybackRequest.Live -> PlaybackMediaId.Live(account.id, request.contentId)
        is PlaybackRequest.Movie -> PlaybackMediaId.Movie(account.id, request.contentId)
        is PlaybackRequest.Episode -> PlaybackMediaId.Episode(account.id, request.contentId)
    }
    private val encodedMediaId = mediaId.encode()

    private val mutableState = MutableStateFlow(PlayerUiState(account, request))
    val state: StateFlow<PlayerUiState> = mutableState.asStateFlow()

    private var recentJob: Job? = null
    private var epgJob: Job? = null
    private var nextEpisodeJob: Job? = null
    private var checkpointJob: Job? = null
    private var countdownJob: Job? = null
    private var wasPlaying = false

    init {
        viewModelScope.launch {
            connection.snapshot.collect { snapshot ->
                mutableState.update {
                    it.copy(
                        snapshot = snapshot,
                        isStarting = snapshot.playbackState == Player.STATE_IDLE &&
                            snapshot.error == null && it.startError == null,
                    )
                }
                presentationState.update(presentationOwner, snapshot)
                if (snapshot.mediaId != encodedMediaId) return@collect
                when (request) {
                    is PlaybackRequest.Live -> if (snapshot.isPlaying) scheduleRecentWrite()
                    is PlaybackRequest.Resumable -> onResumableSnapshot(snapshot)
                }
            }
        }
        loadAndPlay()
    }

    fun onScreenVisible() {
        presentationState.show(presentationOwner, mutableState.value.snapshot)
    }

    fun onScreenHidden() {
        // Covers backgrounding and the PiP transition, where no explicit stop arrives.
        checkpoint()
        coordinator.endTemporarySpeed()
        presentationState.hide(presentationOwner)
    }

    fun loadAndPlay() {
        viewModelScope.launch {
            mutableState.update { it.copy(isStarting = true, startError = null) }
            try {
                when (request) {
                    is PlaybackRequest.Live -> startLive(request)
                    is PlaybackRequest.Movie -> startMovie(request)
                    is PlaybackRequest.Episode -> startEpisode(request)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                mutableState.update { it.copy(isStarting = false, startError = failure.failure) }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isStarting = false,
                        startError = AppFailure(FailureKind.StreamUnavailable, true),
                    )
                }
            }
        }
    }

    private suspend fun startLive(request: PlaybackRequest.Live) {
        val channel = liveRepository.getChannel(account.id, request.contentId)
        if (channel == null) {
            reportMissingContent()
            return
        }
        mutableState.update { it.copy(title = channel.name) }
        coordinator.playLive(account, channel)
        loadEpg(request.contentId)
    }

    /**
     * Fetched after playback has been asked to start, never before it.
     *
     * The guide is decoration around the picture; making the first frame wait on a metadata
     * request would trade the thing the viewer asked for against one they did not.
     */
    private fun loadEpg(streamId: String) {
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val entries = liveRepository.epg(account.id, streamId)
            mutableState.update { it.copy(epg = entries) }
        }
    }

    private suspend fun startMovie(request: PlaybackRequest.Movie) {
        val movie = movieRepository.getMovie(account.id, request.contentId)
        if (movie == null) {
            reportMissingContent()
            return
        }
        mutableState.update { it.copy(title = movie.name) }
        coordinator.playMovie(account, movie, resumePositionFor(request))
    }

    private suspend fun startEpisode(request: PlaybackRequest.Episode) {
        val episode = seriesRepository.getEpisode(account.id, request.contentId)
        if (episode == null) {
            reportMissingContent()
            return
        }
        mutableState.update { it.copy(title = episode.displayLabel) }
        coordinator.playEpisode(account, episode, resumePositionFor(request))
        loadNeighbouringEpisodes(request.contentId)
    }

    /** Resolved while playback starts, so both controls are ready before they are needed. */
    private fun loadNeighbouringEpisodes(episodeId: String) {
        nextEpisodeJob?.cancel()
        nextEpisodeJob = viewModelScope.launch {
            val next = runCatching { seriesRepository.nextEpisode(account.id, episodeId) }
                .getOrNull()
            val previous = runCatching { seriesRepository.previousEpisode(account.id, episodeId) }
                .getOrNull()
            mutableState.update { it.copy(nextEpisode = next, previousEpisode = previous) }
        }
    }

    /**
     * Called when the screen has acted on [PlayerUiState.autoAdvanceTo], so the same episode is
     * never started twice by a recomposition.
     */
    fun consumeAutoAdvance() = mutableState.update { it.copy(autoAdvanceTo = null) }

    /**
     * A restart, or a title already counted as watched, starts from the beginning. Resuming a
     * completed title would drop the viewer three minutes before the credits.
     */
    private suspend fun resumePositionFor(request: PlaybackRequest.Resumable): Long {
        if (request.restart) return 0L
        val progress = when (request) {
            is PlaybackRequest.Movie -> movieRepository.progress(account.id, request.contentId)
            is PlaybackRequest.Episode ->
                seriesRepository.episodeProgress(account.id, request.contentId)
        } ?: return 0L
        return if (progress.completed) 0L else progress.positionMs.coerceAtLeast(0L)
    }

    private fun reportMissingContent() = mutableState.update {
        it.copy(isStarting = false, startError = AppFailure(FailureKind.StreamUnavailable))
    }

    fun retry() {
        if (connection.snapshot.value.mediaId == encodedMediaId) {
            mutableState.update { it.copy(startError = null) }
            coordinator.retry()
        } else {
            coordinator.reconnect()
            loadAndPlay()
        }
    }

    fun seekBy(deltaMs: Long): Boolean = coordinator.seekBy(deltaMs)

    fun togglePlayPause(): Boolean = coordinator.togglePlayPause()

    fun beginTemporarySpeed(speed: Float): Boolean = coordinator.beginTemporarySpeed(speed)

    fun endTemporarySpeed() = coordinator.endTemporarySpeed()

    fun stop() {
        recentJob?.cancel()
        checkpointJob?.cancel()
        // Leaving the player is a decision against the next episode too.
        cancelAutoAdvance()
        // The position has to be read before the item is cleared; afterwards there is none.
        checkpoint()
        coordinator.stopAndClear()
    }

    private fun onResumableSnapshot(snapshot: PlaybackSnapshot) {
        if (snapshot.isPlaying) {
            startCheckpointTicker()
        } else {
            checkpointJob?.cancel()
            // A pause, a stall, or the end of the title are all worth recording immediately.
            if (wasPlaying || snapshot.playbackState == Player.STATE_ENDED) checkpoint()
        }
        wasPlaying = snapshot.isPlaying
        if (snapshot.playbackState == Player.STATE_ENDED) onPlaybackEnded()
    }

    /**
     * Starts the countdown to the next episode when one exists and autoplay is on.
     *
     * The checkpoint above has already run, so the finished episode is marked watched before the
     * next one starts — otherwise the series would offer the one just watched again.
     *
     * `STATE_ENDED` can be reported more than once for the same ending, so an already running
     * countdown is left alone rather than restarted; otherwise the timer would never reach zero.
     */
    private fun onPlaybackEnded() {
        if (!autoPlayNextEpisode) return
        val next = mutableState.value.nextEpisode ?: return
        if (mutableState.value.autoAdvanceTo != null) return
        if (countdownJob?.isActive == true) return
        countdownJob = viewModelScope.launch {
            for (remaining in AUTO_ADVANCE_COUNTDOWN_SECONDS downTo 1) {
                mutableState.update { it.copy(autoAdvanceCountdownSeconds = remaining) }
                delay(1_000L)
            }
            mutableState.update {
                it.copy(autoAdvanceTo = next, autoAdvanceCountdownSeconds = null)
            }
        }
    }

    /**
     * Stops a running countdown. The episode stays finished and stays marked watched: the viewer
     * declined the next one, not the fact that they watched this one.
     */
    fun cancelAutoAdvance() {
        countdownJob?.cancel()
        countdownJob = null
        mutableState.update { it.copy(autoAdvanceCountdownSeconds = null) }
    }

    private fun startCheckpointTicker() {
        if (checkpointJob?.isActive == true) return
        checkpointJob = viewModelScope.launch {
            while (isActive) {
                delay(CHECKPOINT_INTERVAL_MS)
                checkpoint()
            }
        }
    }

    /**
     * Best effort by design: no controller, no duration yet, or an item that belongs to another
     * title simply writes nothing rather than storing a position that would mislead a later
     * resume.
     */
    private fun checkpoint() {
        // Live has no meaningful position, and the type is what rules it out rather than a check.
        val resumable = mediaId as? PlaybackMediaId.Resumable ?: return
        val position = connection.capturePosition() ?: return
        progressWriter.checkpoint(position, resumable)
    }

    private fun scheduleRecentWrite() {
        if (recentJob?.isActive == true) return
        recentJob = viewModelScope.launch {
            delay(RECENT_DELAY_MS)
            if (connection.snapshot.value.isPlaying) {
                liveRepository.markRecent(account.id, request.contentId)
            }
        }
    }

    override fun onCleared() {
        // The write itself runs on an application-owned scope, so it survives this scope ending.
        checkpoint()
        coordinator.endTemporarySpeed()
        presentationState.hide(presentationOwner)
        super.onCleared()
    }

    private companion object {
        const val RECENT_DELAY_MS = 2_000L

        /**
         * Bounded so a two-hour film costs a few hundred small writes rather than one per frame.
         * After a force-stop, Android may deliver no final callback at all, so the expected loss
         * is about one interval.
         */
        const val CHECKPOINT_INTERVAL_MS = 10_000L

        /**
         * Long enough to notice and stop it, short enough not to feel like waiting. The viewer who
         * wants the next episode immediately has the Next control right there.
         */
        const val AUTO_ADVANCE_COUNTDOWN_SECONDS = 8
    }
}
