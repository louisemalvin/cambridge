package dev.mobilewebcam.sender.model

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Streaming(
        val session: StreamSession,
        val startedAtMillis: Long,
    ) : StreamState
    data object Stopping : StreamState
    data object Reconnecting : StreamState
    data class Failed(val failure: StreamFailure) : StreamState
}
