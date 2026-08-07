package dev.mobilewebcam.sender.model

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data class Streaming(
        val session: StreamSession,
        val startedAtMillis: Long,
    ) : StreamState
    data object Stopping : StreamState
    data class Failed(val failure: StreamFailure) : StreamState
}

val StreamState.isSessionActive: Boolean
    get() = this is StreamState.Connecting ||
        this is StreamState.Streaming ||
        this is StreamState.Stopping

val StreamState.requiresStopConfirmation: Boolean
    get() = this is StreamState.Connecting ||
        this is StreamState.Streaming
