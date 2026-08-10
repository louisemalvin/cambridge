package dev.cambridge.sender.model

sealed interface ReceiverProbeState {
    data object Idle : ReceiverProbeState

    data object Checking : ReceiverProbeState

    data object SelectionRequired : ReceiverProbeState

    data class Available(
        val endpoint: ReceiverEndpoint,
        val capabilities: ReceiverCapabilities,
    ) : ReceiverProbeState

    data class Unavailable(
        val endpoint: ReceiverEndpoint,
        val reason: String,
    ) : ReceiverProbeState
}
