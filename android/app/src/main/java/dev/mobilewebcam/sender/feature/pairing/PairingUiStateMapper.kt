package dev.mobilewebcam.sender.feature.pairing

import dev.mobilewebcam.sender.app.model.StreamPresentationMapper
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.model.StreamState

object PairingUiStateMapper {
    fun map(snapshot: PairingDomainSnapshot): PairingUiState = when {
        snapshot.streamState is StreamState.Streaming -> PairingUiState.Connected(
            snapshot.activeReceiverName?.let(UiText::Plain) ?: UiText.Plain(DEFAULT_RECEIVER_NAME),
        )
        snapshot.streamState is StreamState.Failed -> PairingUiState.Failed(
            UiText.Plain(StreamPresentationMapper.failureMessage(snapshot.streamState.failure)),
        )
        snapshot.streamState == StreamState.Connecting ||
            snapshot.streamState == StreamState.Reconnecting -> PairingUiState.Connecting(
                if (snapshot.streamState == StreamState.Reconnecting) {
                    UiText.Plain(RECONNECTING_MESSAGE)
                } else {
                    UiText.Plain(CONNECTING_MESSAGE)
                },
            )
        else -> PairingUiState.Searching(UiText.Plain(CONNECTION_MESSAGE))
    }

    private const val DEFAULT_RECEIVER_NAME = "Receiver"
    private const val CONNECTING_MESSAGE = "Connecting..."
    private const val RECONNECTING_MESSAGE = "Reconnecting..."
    private const val CONNECTION_MESSAGE = "Connect to your OBS computer"
}

sealed interface PairingUiEffect {
    data object NavigateToWebcam : PairingUiEffect
}

object PairingUiEffectMapper {
    fun map(previous: StreamState, current: StreamState): PairingUiEffect? =
        if (current is StreamState.Streaming && previous !is StreamState.Streaming) {
            PairingUiEffect.NavigateToWebcam
        } else {
            null
        }
}
