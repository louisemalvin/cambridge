package dev.mobilewebcam.sender.feature.pairing

import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.StreamState

data class ReceiverOriginDraft(
    val name: String = "",
    val host: String = "",
    val controlPort: String = "",
    val token: String = "",
    val authenticationRequired: Boolean = false,
) {
    fun endpointOrNull(): ReceiverEndpoint? {
        val port = controlPort.toIntOrNull() ?: return null
        return ReceiverEndpoint(
            host = host.trim(),
            controlPort = port,
            displayName = name.trim(),
            controlToken = token.trim().takeIf(String::isNotEmpty),
            authenticationRequired = authenticationRequired,
        ).takeIf(ReceiverEndpoint::isValid)
    }

    companion object {
        fun from(endpoint: ReceiverEndpoint?): ReceiverOriginDraft = endpoint?.let {
            ReceiverOriginDraft(
                name = it.displayName,
                host = it.host,
                controlPort = it.controlPort.toString(),
                token = it.controlToken.orEmpty(),
                authenticationRequired = it.authenticationRequired,
            )
        } ?: ReceiverOriginDraft()
    }
}

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
