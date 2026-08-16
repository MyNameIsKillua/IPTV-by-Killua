package dev.killua.iptv.feature.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Declaration order is the run order, and the screen compares against it to mark steps done. */
enum class SyncStep { Channels, Movies, Series }

data class InitialSyncUiState(
    val step: SyncStep = SyncStep.Channels,
    val channelCount: Int = 0,
    val movieCount: Int = 0,
    val seriesCount: Int = 0,
    /** Libraries that were already cached and are therefore not downloaded again. */
    val cachedSteps: Set<SyncStep> = emptySet(),
    val isRunning: Boolean = false,
    val isFinished: Boolean = false,
    val errorMessage: String? = null,
    /** True when a usable cache already exists, so the user may skip a failed sync. */
    val canContinueWithCache: Boolean = false,
) {
    fun countOf(step: SyncStep): Int = when (step) {
        SyncStep.Channels -> channelCount
        SyncStep.Movies -> movieCount
        SyncStep.Series -> seriesCount
    }

    fun wasAlreadyCached(step: SyncStep): Boolean = step in cachedSteps

    fun isActive(step: SyncStep): Boolean = isRunning && this.step == step && step !in cachedSteps

    fun isDone(step: SyncStep): Boolean = isFinished || step in cachedSteps || this.step > step
}

/**
 * Runs the first library sync behind a progress screen.
 *
 * A large provider takes well over a minute per library, and doing that lazily when a tab is first
 * opened left the user staring at an empty screen with no explanation. Progress is reported per
 * written batch, so the counts move continuously rather than jumping at the end.
 *
 * A library that is **already cached is skipped**. Without that, adding a library to this screen
 * would make the next launch re-download everything the user already has, which for the reference
 * provider is several minutes for no gain. It also means a retry after a partial failure resumes
 * rather than starting over.
 *
 * A failure never blocks entry: whatever was cached before stays usable, and the user can retry
 * or continue.
 */
class InitialSyncViewModel(
    private val account: Account,
    private val liveRepository: LiveRepository,
    private val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(InitialSyncUiState())
    val state: StateFlow<InitialSyncUiState> = mutableState.asStateFlow()

    init {
        start()
    }

    fun start() {
        if (mutableState.value.isRunning) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isRunning = true,
                    errorMessage = null,
                    step = SyncStep.Channels,
                    channelCount = 0,
                    movieCount = 0,
                    seriesCount = 0,
                )
            }
            try {
                val cached = cachedSteps()
                mutableState.update { it.copy(cachedSteps = cached) }

                if (SyncStep.Channels !in cached) {
                    val live = liveRepository.refresh(account.id) { count ->
                        mutableState.update { it.copy(channelCount = count) }
                    }
                    mutableState.update { it.copy(channelCount = live.channelCount) }
                }

                mutableState.update { it.copy(step = SyncStep.Movies) }
                if (SyncStep.Movies !in cached) {
                    val movies = movieRepository.refresh(account.id) { count ->
                        mutableState.update { it.copy(movieCount = count) }
                    }
                    mutableState.update { it.copy(movieCount = movies.movieCount) }
                }

                mutableState.update { it.copy(step = SyncStep.Series) }
                if (SyncStep.Series !in cached) {
                    val series = seriesRepository.refresh(account.id) { count ->
                        mutableState.update { it.copy(seriesCount = count) }
                    }
                    mutableState.update { it.copy(seriesCount = series.seriesCount) }
                }

                mutableState.update { it.copy(isRunning = false, isFinished = true) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                fail(failure.failure)
            } catch (_: OutOfMemoryError) {
                fail(AppFailure(FailureKind.LibraryTooLarge))
            } catch (_: Exception) {
                fail(AppFailure(FailureKind.Unknown))
            }
        }
    }

    /** Enters the app with whatever is cached, without waiting for a successful sync. */
    fun skip() = mutableState.update { it.copy(isRunning = false, isFinished = true) }

    private suspend fun cachedSteps(): Set<SyncStep> = buildSet {
        if (hasCache { liveRepository.hasCachedLibrary(account.id) }) add(SyncStep.Channels)
        if (hasCache { movieRepository.hasCachedLibrary(account.id) }) add(SyncStep.Movies)
        if (hasCache { seriesRepository.hasCachedLibrary(account.id) }) add(SyncStep.Series)
    }

    /** A failed check means "download it": a needless refresh is safer than a skipped one. */
    private suspend fun hasCache(check: suspend () -> Boolean): Boolean = try {
        check()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    }

    private suspend fun fail(failure: AppFailure) {
        val hasCache = hasCache {
            liveRepository.hasCachedLibrary(account.id) ||
                movieRepository.hasCachedLibrary(account.id) ||
                seriesRepository.hasCachedLibrary(account.id)
        }
        mutableState.update {
            it.copy(
                isRunning = false,
                errorMessage = failure.userMessage(),
                canContinueWithCache = hasCache,
            )
        }
    }
}
