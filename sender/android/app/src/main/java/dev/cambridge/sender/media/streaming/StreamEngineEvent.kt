package dev.cambridge.sender.media.streaming

import dev.cambridge.sender.model.StreamFailure

sealed interface StreamEngineEvent {
    val generation: Long

    data class ConnectionStarted(
        val endpoint: String,
        override val generation: Long,
    ) : StreamEngineEvent

    data class Connected(
        override val generation: Long,
    ) : StreamEngineEvent

    data class ConnectionFailed(
        val reason: String,
        override val generation: Long,
    ) : StreamEngineEvent

    data class FatalFailure(
        val failure: StreamFailure,
        override val generation: Long,
    ) : StreamEngineEvent

    data class Disconnected(
        override val generation: Long,
    ) : StreamEngineEvent

    data class AuthenticationError(
        override val generation: Long,
    ) : StreamEngineEvent

    data class AuthenticationSucceeded(
        override val generation: Long,
    ) : StreamEngineEvent

    data class BitrateChanged(
        val bitrateBps: Long,
        override val generation: Long,
    ) : StreamEngineEvent
}
