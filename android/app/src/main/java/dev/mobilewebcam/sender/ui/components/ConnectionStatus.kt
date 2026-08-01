package dev.mobilewebcam.sender.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState

@Composable
fun ConnectionStatus(state: StreamState) {
    Text(statusText(state))
}

private fun statusText(state: StreamState): String = when (state) {
    StreamState.Idle -> "Ready"
    StreamState.CheckingReceiver -> "Checking receiver"
    StreamState.Negotiating -> "Negotiating codec"
    is StreamState.Preparing -> "Preparing ${state.codec.protocolId}"
    is StreamState.Starting -> "Starting stream"
    is StreamState.Streaming -> "Streaming ${state.session.selectedCodec.protocolId}"
    StreamState.Stopping -> "Stopping"
    is StreamState.Failed -> "Error: ${failureMessage(state.failure)}"
}

fun failureMessage(failure: StreamFailure): String = when (failure) {
    StreamFailure.CameraPermissionDenied -> "Camera permission is required"
    StreamFailure.CameraUnavailable -> "Camera is unavailable"
    is StreamFailure.ReceiverUnavailable -> failure.reason
    is StreamFailure.NoCompatibleCodec -> "No compatible codec supports this profile"
    is StreamFailure.ForcedCodecUnsupported ->
        "${failure.codec.protocolId} is not available for this profile"
    is StreamFailure.ReceiverRejectedProfile -> failure.reason
    is StreamFailure.EncoderPreparationFailed -> "${failure.codec.protocolId} encoder preparation failed"
    is StreamFailure.StreamStartFailed -> "The media stream could not start"
    StreamFailure.NetworkDisconnected -> "The network connection was interrupted"
    is StreamFailure.Unexpected -> "An unexpected streaming error occurred"
}
