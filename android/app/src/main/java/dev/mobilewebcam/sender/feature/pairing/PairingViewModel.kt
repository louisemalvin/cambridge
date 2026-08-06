package dev.mobilewebcam.sender.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.StreamState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val coordinator: SenderConnectionCoordinator,
) : ViewModel() {
    val uiState: StateFlow<PairingUiState> = combine(
        coordinator.streamState,
        coordinator.activeReceiverName,
    ) { streamState, receiverName ->
        PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = streamState,
                activeReceiverName = receiverName,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PairingUiState.Idle,
    )
}
