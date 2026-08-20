package dev.cambridge.sender.media.streaming

import dev.cambridge.sender.model.StreamFailure

sealed interface StreamEngineEvent {
    data class ConnectionStarted(val endpoint: String) : StreamEngineEvent
    data object Connected : StreamEngineEvent
    data class ConnectionFailed(val reason: String) : StreamEngineEvent
    data class FatalFailure(val failure: StreamFailure) : StreamEngineEvent
    data object Disconnected : StreamEngineEvent
    data object AuthenticationError : StreamEngineEvent
    data object AuthenticationSucceeded : StreamEngineEvent
    data class BitrateChanged(val bitrateBps: Long) : StreamEngineEvent
}
