package dev.mobilewebcam.sender.model

sealed interface StreamState {
    data object Idle : StreamState
    data object ConnectedStandby : StreamState
    data object CheckingReceiver : StreamState
    data object Negotiating : StreamState
    data class Preparing(val codec: VideoCodec, val profile: VideoProfile) : StreamState
    data class Starting(val session: NegotiatedSession) : StreamState
    data class Streaming(
        val session: NegotiatedSession,
        val startedAtMillis: Long,
    ) : StreamState
    data object Stopping : StreamState
    data class Failed(val failure: StreamFailure) : StreamState
}
