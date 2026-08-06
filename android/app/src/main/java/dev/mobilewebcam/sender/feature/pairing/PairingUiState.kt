package dev.mobilewebcam.sender.feature.pairing

import androidx.compose.runtime.Immutable
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.model.StreamState

@Immutable
sealed interface PairingUiState {
    data object Idle : PairingUiState
    data class Searching(val message: UiText) : PairingUiState
    data class Connecting(val message: UiText) : PairingUiState
    data class Connected(val receiverName: UiText) : PairingUiState
    data class Failed(val message: UiText) : PairingUiState
}

data class PairingDomainSnapshot(
    val streamState: StreamState,
    val activeReceiverName: String?,
)
