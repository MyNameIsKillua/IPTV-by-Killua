package dev.killua.iptv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.core.preferences.AppPreferences
import dev.killua.iptv.core.preferences.PlaybackGestureOptions
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.ThemeMode
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val account: Account,
    val isRefreshing: Boolean = false,
    val isReconnecting: Boolean = false,
    val isClearingArtworkCache: Boolean = false,
    val isLoggingOut: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

class SettingsViewModel(
    account: Account,
    private val sessionRepository: SessionRepository,
    private val liveRepository: LiveRepository,
    private val preferences: AppPreferences,
    private val stopPlayback: () -> Unit,
    private val clearArtworkCache: suspend () -> Unit,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState(account))
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    val themeMode = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.Dark)
    val pictureInPictureEnabled = preferences.pictureInPictureEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val autoPlayNextEpisode = preferences.autoPlayNextEpisode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val doubleTapSeekSeconds = preferences.doubleTapSeekSeconds
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PlaybackGestureOptions.defaultSeekSeconds,
        )
    val holdPlaybackSpeed = preferences.holdPlaybackSpeed
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PlaybackGestureOptions.defaultHoldSpeedHundredths / 100f,
        )

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setPictureInPicture(enabled: Boolean) {
        viewModelScope.launch { preferences.setPictureInPictureEnabled(enabled) }
    }

    fun setAutoPlayNextEpisode(enabled: Boolean) {
        viewModelScope.launch { preferences.setAutoPlayNextEpisode(enabled) }
    }

    fun setDoubleTapSeekSeconds(seconds: Int) {
        viewModelScope.launch { preferences.setDoubleTapSeekSeconds(seconds) }
    }

    fun setHoldPlaybackSpeed(speed: Float) {
        viewModelScope.launch { preferences.setHoldPlaybackSpeed(speed) }
    }

    fun clearArtworkCache() {
        if (mutableState.value.isClearingArtworkCache) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(isClearingArtworkCache = true, message = null, errorMessage = null)
            }
            try {
                clearArtworkCache.invoke()
                mutableState.update {
                    it.copy(
                        isClearingArtworkCache = false,
                        message = "Channel artwork cache cleared.",
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isClearingArtworkCache = false,
                        errorMessage = "The artwork cache could not be cleared. Please try again.",
                    )
                }
            }
        }
    }

    fun refreshLibrary() {
        if (mutableState.value.isRefreshing) return
        viewModelScope.launch {
            mutableState.update { it.copy(isRefreshing = true, message = null, errorMessage = null) }
            try {
                val result = liveRepository.refresh(mutableState.value.account.id)
                mutableState.update {
                    it.copy(
                        isRefreshing = false,
                        message = "Updated ${result.channelCount} channels in ${result.categoryCount} categories.",
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showFailure(failure.failure)
            } catch (_: Exception) {
                showFailure(AppFailure(FailureKind.Unknown))
            }
        }
    }

    /**
     * Renames the playlist.
     *
     * The account held here is a value rather than a flow, so it is updated alongside the store;
     * otherwise this screen would keep showing the old name until it was rebuilt.
     */
    fun rename(displayName: String) {
        viewModelScope.launch {
            mutableState.update { it.copy(message = null, errorMessage = null) }
            try {
                sessionRepository.renameAccount(displayName)
                val trimmed = displayName.trim().takeIf { it.isNotBlank() }
                mutableState.update {
                    it.copy(
                        account = it.account.copy(displayName = trimmed),
                        message = if (trimmed == null) "Playlist name cleared." else "Playlist renamed.",
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showFailure(failure.failure)
            } catch (_: Exception) {
                showFailure(AppFailure(FailureKind.Unknown))
            }
        }
    }

    fun reconnect() {
        if (mutableState.value.isReconnecting) return
        viewModelScope.launch {
            mutableState.update { it.copy(isReconnecting = true, message = null, errorMessage = null) }
            try {
                val account = sessionRepository.reconnect()
                mutableState.update {
                    it.copy(account = account, isReconnecting = false, message = "Account reconnected.")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showFailure(failure.failure)
            } catch (_: Exception) {
                showFailure(AppFailure(FailureKind.Unknown))
            }
        }
    }

    /**
     * Signing out deletes every cached row for the account. On a large provider that is hundreds
     * of thousands of rows and takes several seconds, and it additionally waits for any refresh
     * still running. Without a busy state the screen looked idle and stayed scrollable, so the
     * action appeared to have done nothing.
     */
    fun logout() {
        if (mutableState.value.isLoggingOut) return
        viewModelScope.launch {
            mutableState.update { it.copy(isLoggingOut = true, errorMessage = null) }
            stopPlayback()
            try {
                sessionRepository.logout()
                // Leaving authenticated state replaces this screen, so the busy flag stays set
                // until the ViewModel is cleared.
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                mutableState.update { it.copy(isLoggingOut = false) }
                showFailure(failure.failure)
            } catch (_: Exception) {
                mutableState.update { it.copy(isLoggingOut = false) }
                showFailure(AppFailure(FailureKind.Unknown))
            }
        }
    }

    private fun showFailure(failure: AppFailure) {
        mutableState.update {
            it.copy(
                isRefreshing = false,
                isReconnecting = false,
                isClearingArtworkCache = false,
                errorMessage = failure.userMessage(),
            )
        }
    }
}
