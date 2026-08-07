package dev.cambridge.sender.media.streaming

sealed interface StreamEngineEvent {
    data class ConnectionStarted(val endpoint: String) : StreamEngineEvent
    data object Connected : StreamEngineEvent
    data class ConnectionFailed(val reason: String) : StreamEngineEvent
    data object Disconnected : StreamEngineEvent
    data object AuthenticationError : StreamEngineEvent
    data object AuthenticationSucceeded : StreamEngineEvent
    data class BitrateChanged(val bitrateBps: Long) : StreamEngineEvent
}
