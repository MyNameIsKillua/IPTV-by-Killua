package dev.killua.iptv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.core.preferences.AppPreferences
import dev.killua.iptv.core.preferences.PlaybackGestureOptions
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.SubtitleBackground
import dev.killua.iptv.domain.model.SubtitleStyle
import dev.killua.iptv.domain.model.SubtitleTextSize
import dev.killua.iptv.domain.model.ThemeMode
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.userdata.UserDataImportPlan
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
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    /** Set once a file has been read and understood, so the viewer can approve it before anything moves. */
    val pendingImport: UserDataImportPlan.Ready? = null,
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
    /** Also resets the player's own memory of what it last learned; see `TrackLanguageWriter`. */
    private val clearTrackLanguages: suspend () -> Unit,
    /** Produces the export document for the active account. Never returns credentials. */
    private val buildUserDataExport: suspend (accountId: String) -> String,
    private val planUserDataImport: suspend (accountId: String, document: String) -> UserDataImportPlan,
    private val applyUserDataImport: suspend (accountId: String, plan: UserDataImportPlan.Ready) -> Int,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState(account))
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()
    val themeMode = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.Dark)
    val pictureInPictureEnabled = preferences.pictureInPictureEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val autoPlayNextEpisode = preferences.autoPlayNextEpisode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val updateCheckEnabled = preferences.updateCheckEnabled
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
    val trackLanguages = preferences.trackLanguages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackLanguagePreferences())
    val subtitleStyle = preferences.subtitleStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleStyle())

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setUpdateCheckEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setUpdateCheckEnabled(enabled) }
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

    fun setSubtitleTextSize(size: SubtitleTextSize) {
        viewModelScope.launch { preferences.setSubtitleTextSize(size) }
    }

    fun setSubtitleBackground(background: SubtitleBackground) {
        viewModelScope.launch { preferences.setSubtitleBackground(background) }
    }

    /**
     * Forgets the remembered audio and subtitle languages, so the player decides again.
     *
     * There is no picker here on purpose: which languages exist is a property of the stream, and a
     * list this screen invented would offer languages the provider does not carry. The choice is
     * made in the player, on a title that actually has the track; this row shows the result of that
     * and takes it back.
     */
    fun clearTrackLanguages() {
        viewModelScope.launch {
            clearTrackLanguages.invoke()
            mutableState.update { it.copy(message = "Audio and subtitle languages cleared.") }
        }
    }

    /**
     * Writes the export through [sink], which the screen supplies because only it has the document
     * the viewer picked. The file is created by the system picker, so this never needs storage
     * permission and never decides where the file goes.
     *
     * The document is built first and handed over whole: a failure part-way through assembling it
     * then leaves no half-written file behind.
     */
    fun exportUserData(sink: suspend (String) -> Unit) {
        if (mutableState.value.isExporting) return
        viewModelScope.launch {
            mutableState.update { it.copy(isExporting = true, message = null, errorMessage = null) }
            try {
                val document = buildUserDataExport(mutableState.value.account.id)
                sink(document)
                mutableState.update {
                    it.copy(isExporting = false, message = "Your data was exported.")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = "The export could not be written. Please try again.",
                    )
                }
            }
        }
    }

    /**
     * Reads a chosen file and works out what it would change, writing nothing yet.
     *
     * A file that is unreadable or belongs to another provider account is refused here, before the
     * viewer is ever asked to confirm - saying yes to a merge that then fails is worse than being
     * told no straight away.
     */
    fun prepareUserDataImport(document: String) {
        if (mutableState.value.isImporting) return
        viewModelScope.launch {
            mutableState.update { it.copy(isImporting = true, message = null, errorMessage = null) }
            try {
                when (val plan = planUserDataImport(mutableState.value.account.id, document)) {
                    is UserDataImportPlan.Ready -> mutableState.update {
                        if (plan.changeCount == 0) {
                            it.copy(
                                isImporting = false,
                                message = "That file holds nothing this device does not already have.",
                            )
                        } else {
                            it.copy(isImporting = false, pendingImport = plan)
                        }
                    }
                    UserDataImportPlan.WrongAccount -> mutableState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = "That file belongs to a different account.",
                        )
                    }
                    is UserDataImportPlan.Unreadable -> mutableState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = "That file is not a Killua IPTV export, or it was written by a newer version.",
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = "The file could not be read. Please try again.",
                    )
                }
            }
        }
    }

    /** The picked document could not be read at all, or was implausibly large to be an export. */
    fun reportImportUnreadable() = mutableState.update {
        it.copy(
            isImporting = false,
            errorMessage = "That file could not be read as an export.",
        )
    }

    fun cancelUserDataImport() = mutableState.update { it.copy(pendingImport = null) }

    /** Writes the plan the viewer approved. Nothing is ever deleted; see `UserDataMerge`. */
    fun confirmUserDataImport() {
        val plan = mutableState.value.pendingImport ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(isImporting = true, pendingImport = null) }
            try {
                val changed = applyUserDataImport(mutableState.value.account.id, plan)
                mutableState.update {
                    it.copy(isImporting = false, message = "Imported $changed entries.")
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                mutableState.update { it.copy(isImporting = false) }
                showFailure(failure.failure)
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = "The import could not be completed. Nothing was changed.",
                    )
                }
            }
        }
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
                isExporting = false,
                isImporting = false,
                errorMessage = failure.userMessage(),
            )
        }
    }
}
