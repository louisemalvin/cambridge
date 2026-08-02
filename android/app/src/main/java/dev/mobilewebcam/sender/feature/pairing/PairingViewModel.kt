package dev.mobilewebcam.sender.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilewebcam.sender.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.ui.SenderScreenStateMapper
import dev.mobilewebcam.sender.ui.model.UiText
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PairingViewModel(
    private val coordinator: SenderConnectionCoordinator,
) : ViewModel() {
    val uiState: StateFlow<PairingUiState> = combine(
        coordinator.pendingApproval,
        coordinator.streamState,
        coordinator.activeReceiverName,
    ) { pending, streamState, receiverName ->
        when {
            pending != null -> PairingUiState.AwaitingApproval(UiText.Plain(pending.receiverName))
            streamState is StreamState.Streaming -> PairingUiState.Connected(
                receiverName?.let(UiText::Plain) ?: UiText.Plain("Receiver"),
            )
            streamState is StreamState.Failed -> PairingUiState.Failed(
                UiText.Plain(SenderScreenStateMapper.failureMessage(streamState.failure)),
            )
            streamState is StreamState.Preparing || streamState is StreamState.Starting ->
                PairingUiState.Connecting(UiText.Plain("Connecting..."))
            else -> PairingUiState.Searching(UiText.Plain("Searching for receivers..."))
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PairingUiState.Idle,
    )

    fun approvePending() {
        viewModelScope.launch { coordinator.approvePending() }
    }

    fun rejectPending() {
        viewModelScope.launch { coordinator.rejectPending() }
    }
}
