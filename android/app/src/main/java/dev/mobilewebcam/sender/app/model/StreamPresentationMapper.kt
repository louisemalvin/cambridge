package dev.mobilewebcam.sender.app.model

import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.session.VideoProfiles

object StreamPresentationMapper {
    fun connection(snapshot: StreamPresentationSnapshot): ConnectionUiState = when (
        val streamState = snapshot.streamState
    ) {
        StreamState.Idle -> ConnectionUiState.Waiting
        StreamState.CheckingReceiver -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.checking_receiver),
        )
        StreamState.Negotiating -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.negotiating_codec),
        )
        is StreamState.Preparing -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.preparing_stream, listOf(codecName(streamState.codec))),
        )
        is StreamState.Starting -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.starting_stream),
        )
        is StreamState.Streaming -> ConnectionUiState.Streaming(
            codec = UiText.Resource(
                R.string.streaming_codec,
                listOf(codecName(streamState.session.selectedCodec)),
            ),
            receiverName = snapshot.activeReceiverName?.let(UiText::Plain),
            profile = videoProfileLabel(streamState.session.profile),
        )
        StreamState.Stopping -> ConnectionUiState.Stopping
        is StreamState.Failed -> ConnectionUiState.Failed(
            UiText.Plain(failureMessage(streamState.failure)),
        )
    }

    fun connectionStatus(streamState: StreamState): UiText? = when (streamState) {
        StreamState.Idle -> UiText.Resource(R.string.not_connected)
        StreamState.CheckingReceiver -> UiText.Resource(R.string.checking_receiver)
        StreamState.Negotiating -> UiText.Resource(R.string.negotiating_codec)
        is StreamState.Preparing -> UiText.Resource(
            R.string.preparing_stream,
            listOf(codecName(streamState.codec)),
        )
        is StreamState.Starting -> UiText.Resource(R.string.starting_stream)
        is StreamState.Streaming -> UiText.Resource(
            R.string.streaming_codec,
            listOf(codecName(streamState.session.selectedCodec)),
        )
        StreamState.Stopping -> UiText.Resource(R.string.stopping_stream)
        is StreamState.Failed -> UiText.Plain(failureMessage(streamState.failure))
    }

    fun codecPreferenceLabel(preference: CodecPreference): UiText = when (preference) {
        CodecPreference.AUTO_PREFER_H265 -> UiText.Resource(R.string.codec_auto_prefer_h265)
        CodecPreference.FORCE_H264 -> UiText.Resource(R.string.codec_h264)
        CodecPreference.FORCE_H265 -> UiText.Resource(R.string.codec_h265)
    }

    fun videoProfileLabel(profile: VideoProfile): UiText = when (profile.id) {
        VideoProfiles.PROFILE_1080P30.id -> UiText.Resource(R.string.profile_1080p30)
        VideoProfiles.PROFILE_1440P30.id -> UiText.Resource(R.string.profile_1440p30)
        VideoProfiles.PROFILE_4K30.id -> UiText.Resource(R.string.profile_4k30)
        else -> UiText.Plain(profile.id)
    }

    fun failureMessage(failure: StreamFailure): String = when (failure) {
        StreamFailure.CameraPermissionDenied -> "Camera permission was denied"
        StreamFailure.CameraUnavailable -> "The camera is unavailable"
        is StreamFailure.ReceiverUnavailable -> failure.reason
        is StreamFailure.NoCompatibleCodec -> "No compatible codec for ${failure.requestedProfile.id}"
        is StreamFailure.ForcedCodecUnsupported ->
            "Codec ${failure.codec.protocolId} is unsupported for ${failure.requestedProfile.id}"
        is StreamFailure.ReceiverRejectedProfile -> failure.reason
        is StreamFailure.EncoderPreparationFailed ->
            failure.cause?.message ?: "The encoder could not be prepared"
        is StreamFailure.StreamStartFailed ->
            failure.cause?.message ?: "The media stream could not be started"
        StreamFailure.NetworkDisconnected -> "The receiver connection was lost"
        is StreamFailure.Unexpected -> failure.cause.message ?: "An unexpected streaming error occurred"
    }

    fun causeOrNull(failure: StreamFailure): Throwable? = when (failure) {
        is StreamFailure.EncoderPreparationFailed -> failure.cause
        is StreamFailure.StreamStartFailed -> failure.cause
        is StreamFailure.Unexpected -> failure.cause
        else -> null
    }

    fun codecName(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> "H.264"
        VideoCodec.H265 -> "H.265"
    }
}
