package dev.mobilewebcam.sender.feature.pairing

import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.feature.webcam.SenderScreenStateMapper
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

object PairingUiStateMapper {
    fun map(snapshot: PairingDomainSnapshot): PairingUiState = when {
        snapshot.pendingApprovalName != null -> PairingUiState.AwaitingApproval(
            UiText.Plain(snapshot.pendingApprovalName),
        )
        snapshot.streamState is StreamState.Streaming -> PairingUiState.Connected(
            snapshot.activeReceiverName?.let(UiText::Plain) ?: UiText.Plain(DEFAULT_RECEIVER_NAME),
        )
        snapshot.streamState is StreamState.Failed -> PairingUiState.Failed(
            UiText.Plain(SenderScreenStateMapper.failureMessage(snapshot.streamState.failure)),
        )
        snapshot.streamState is StreamState.CheckingReceiver ||
            snapshot.streamState is StreamState.Negotiating ||
            snapshot.streamState is StreamState.Preparing ||
            snapshot.streamState is StreamState.Starting -> PairingUiState.Connecting(
                UiText.Plain(CONNECTING_MESSAGE),
            )
        else -> PairingUiState.Searching(UiText.Plain(SEARCHING_MESSAGE))
    }

    private const val DEFAULT_RECEIVER_NAME = "Receiver"
    private const val CONNECTING_MESSAGE = "Connecting..."
    private const val SEARCHING_MESSAGE = "Searching for receivers..."
}

sealed interface PairingUiEffect {
    data object NavigateToWebcam : PairingUiEffect
}

object PairingUiEffectMapper {
    fun map(previous: StreamState, current: StreamState): PairingUiEffect? =
        if (previous !is StreamState.Streaming && current is StreamState.Streaming) {
            PairingUiEffect.NavigateToWebcam
        } else {
            null
        }
}
