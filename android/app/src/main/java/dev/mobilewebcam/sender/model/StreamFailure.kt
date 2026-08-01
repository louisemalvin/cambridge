package dev.mobilewebcam.sender.model

sealed interface StreamFailure {
    data object CameraPermissionDenied : StreamFailure
    data object CameraUnavailable : StreamFailure
    data class ReceiverUnavailable(val reason: String) : StreamFailure
    data class NoCompatibleCodec(val requestedProfile: VideoProfile) : StreamFailure
    data class ForcedCodecUnsupported(
        val codec: VideoCodec,
        val requestedProfile: VideoProfile,
    ) : StreamFailure
    data class ReceiverRejectedProfile(val reason: String) : StreamFailure
    data class EncoderPreparationFailed(val codec: VideoCodec, val cause: Throwable?) : StreamFailure
    data class StreamStartFailed(val cause: Throwable?) : StreamFailure
    data object NetworkDisconnected : StreamFailure
    data class Unexpected(val cause: Throwable) : StreamFailure
}

class StreamFailureException(
    val failure: StreamFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.toString(), cause)
