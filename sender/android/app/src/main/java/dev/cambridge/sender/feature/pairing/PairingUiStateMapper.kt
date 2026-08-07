package dev.cambridge.sender.feature.pairing

import dev.cambridge.sender.app.model.StreamPresentationMapper
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.model.StreamState

object PairingUiStateMapper {
    fun map(snapshot: PairingDomainSnapshot): PairingUiState = when {
        snapshot.streamState is StreamState.Streaming -> PairingUiState.Connected(
            snapshot.activeReceiverName?.let(UiText::Plain) ?: UiText.Plain(DEFAULT_RECEIVER_NAME),
        )
        snapshot.streamState is StreamState.Failed -> PairingUiState.Failed(
            UiText.Plain(StreamPresentationMapper.failureMessage(snapshot.streamState.failure)),
        )
        snapshot.streamState == StreamState.Connecting -> PairingUiState.Connecting(
            UiText.Plain(CONNECTING_MESSAGE),
        )
        else -> PairingUiState.Searching(UiText.Plain(CONNECTION_MESSAGE))
    }

    private const val DEFAULT_RECEIVER_NAME = "Receiver"
    private const val CONNECTING_MESSAGE = "Connecting..."
    private const val CONNECTION_MESSAGE = "Choose stream settings before starting"
}
