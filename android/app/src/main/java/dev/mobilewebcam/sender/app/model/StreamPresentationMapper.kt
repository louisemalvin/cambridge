package dev.mobilewebcam.sender.app.model

import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.media.camera.SessionTransform
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.session.VideoProfiles

object StreamPresentationMapper {
    fun connection(snapshot: StreamPresentationSnapshot): ConnectionUiState = when (
        val streamState = snapshot.streamState
    ) {
        StreamState.Idle -> ConnectionUiState.Waiting
        StreamState.Connecting -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.connecting_to_computer),
        )
        is StreamState.Streaming -> ConnectionUiState.Streaming(
            status = UiText.Resource(R.string.camera_is_on),
            receiverName = snapshot.activeReceiverName?.let(UiText::Plain),
            profile = videoProfileLabel(streamState.session.profile),
        )
        StreamState.Stopping -> ConnectionUiState.Stopping
        is StreamState.Failed -> ConnectionUiState.Failed(
            UiText.Plain(failureMessage(streamState.failure)),
        )
    }

    fun connectionStatus(streamState: StreamState, activeReceiverName: String? = null): UiText? = when (streamState) {
        StreamState.Idle -> UiText.Resource(R.string.not_connected)
        StreamState.Connecting -> UiText.Resource(R.string.connecting_to_computer)
        is StreamState.Streaming -> UiText.Resource(R.string.camera_is_on)
        StreamState.Stopping -> UiText.Resource(R.string.stopping_stream)
        is StreamState.Failed -> UiText.Plain(failureMessage(streamState.failure))
    }

    fun sessionOrientation(streamState: StreamState): UiText? = when (streamState) {
        is StreamState.Streaming -> streamState.session.sessionTransform?.let(::orientationLabel)
        else -> null
    }

    fun videoProfileLabel(profile: VideoProfile): UiText = when (profile.id) {
        VideoProfiles.PROFILE_2K30.id -> UiText.Resource(R.string.profile_2k30)
        VideoProfiles.PROFILE_720P30.id -> UiText.Resource(R.string.profile_720p30)
        else -> UiText.Plain(profile.id)
    }

    fun failureMessage(failure: StreamFailure): String = when (failure) {
        StreamFailure.CameraPermissionDenied -> "Camera permission was denied"
        StreamFailure.CameraUnavailable -> "The camera is unavailable"
        is StreamFailure.VideoQualityUnsupported -> qualityFailureMessage(failure.requestedProfile)
        is StreamFailure.ReceiverUnavailable -> "OBS is not available"
        is StreamFailure.NoCompatibleCodec -> qualityFailureMessage(failure.requestedProfile)
        is StreamFailure.ForcedCodecUnsupported -> qualityFailureMessage(failure.requestedProfile)
        is StreamFailure.ReceiverRejectedProfile -> "The computer cannot use the selected video quality"
        is StreamFailure.EncoderPreparationFailed -> "This phone cannot use the selected video quality"
        is StreamFailure.StreamStartFailed -> "OBS is not available"
        StreamFailure.NetworkDisconnected -> "Connection lost. Press Start stream to try again"
        is StreamFailure.Unexpected -> "The camera could not start"
    }

    fun causeOrNull(failure: StreamFailure): Throwable? = when (failure) {
        is StreamFailure.EncoderPreparationFailed -> failure.cause
        is StreamFailure.StreamStartFailed -> failure.cause
        is StreamFailure.Unexpected -> failure.cause
        else -> null
    }

    private fun orientationLabel(transform: SessionTransform): UiText = UiText.Resource(
        if (transform.isPortrait) R.string.portrait else R.string.landscape,
    )

    private fun qualityFailureMessage(profile: VideoProfile): String =
        if (profile.id == VideoProfiles.PROFILE_2K30.id) {
            "This phone cannot use 2K video"
        } else {
            "This phone cannot use the selected video quality"
        }
}
