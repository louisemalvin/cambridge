package dev.mobilewebcam.sender.feature.pairing

import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.model.StreamState

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data class Searching(val message: UiText) : PairingUiState
    data class AwaitingApproval(val receiverName: UiText) : PairingUiState
    data class Connecting(val message: UiText) : PairingUiState
    data class Connected(val receiverName: UiText) : PairingUiState
    data class Failed(val message: UiText) : PairingUiState
}

data class ReceiverApprovalUiState(
    val receiverName: UiText,
)

data class PairingDomainSnapshot(
    val pendingApprovalName: String?,
    val streamState: StreamState,
    val activeReceiverName: String?,
)
