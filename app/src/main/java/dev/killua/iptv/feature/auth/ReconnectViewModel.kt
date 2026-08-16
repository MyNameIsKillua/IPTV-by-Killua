package dev.killua.iptv.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.SessionState
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReconnectUiState(
    val isReconnecting: Boolean = false,
    val errorMessage: String? = null,
)

class ReconnectViewModel(
    val session: SessionState.ReconnectRequired,
    private val repository: SessionRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReconnectUiState())
    val state: StateFlow<ReconnectUiState> = mutableState.asStateFlow()

    fun reconnect() {
        if (mutableState.value.isReconnecting) return
        viewModelScope.launch {
            mutableState.value = ReconnectUiState(isReconnecting = true)
            try {
                repository.reconnect()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                mutableState.update {
                    it.copy(isReconnecting = false, errorMessage = failure.failure.userMessage())
                }
            } catch (_: Exception) {
                mutableState.update {
                    it.copy(isReconnecting = false, errorMessage = "The account could not be reconnected.")
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }
}
