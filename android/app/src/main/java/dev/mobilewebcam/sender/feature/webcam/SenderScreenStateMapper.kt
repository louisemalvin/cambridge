package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.LensOptionUi
import dev.mobilewebcam.sender.app.model.PreviewUiState
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.SenderScreenState
import dev.mobilewebcam.sender.app.model.SettingsUiState
import dev.mobilewebcam.sender.app.model.StabilizationUiState
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.ZoomUiState
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.session.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile

data class SenderDomainSnapshot(
    val codecPreference: CodecPreference,
    val profile: VideoProfile,
    val cameraInteraction: CameraInteractionState,
    val streamState: StreamState,
    val cameraPermissionGranted: Boolean,
    val activeReceiverName: String?,
    val validationMessage: String?,
    val isScreenDimmed: Boolean,
    val isZoomTrayOpen: Boolean,
    val isPermissionDialogOpen: Boolean,
)

object SenderScreenStateMapper {
    fun map(snapshot: SenderDomainSnapshot): SenderScreenState {
        val connectionState = mapConnection(snapshot.streamState, snapshot.activeReceiverName)
        return SenderScreenState(
            preview = PreviewUiState(
                landscapeAspectRatio = snapshot.profile.width.toFloat() / snapshot.profile.height,
                isLive = snapshot.streamState is StreamState.Starting ||
                    snapshot.streamState is StreamState.Streaming ||
                    snapshot.streamState == StreamState.Stopping,
            ),
            connection = connectionState,
            camera = CameraControlsUiState(
                zoom = ZoomUiState(
                    ratio = snapshot.cameraInteraction.zoomRatio,
                    minimumRatio = snapshot.cameraInteraction.minZoomRatio,
                    maximumRatio = snapshot.cameraInteraction.maxZoomRatio,
                    isCameraActive = snapshot.cameraInteraction.isCameraActive,
                ),
                lensOptions = snapshot.cameraInteraction.physicalLensOptions.map { lens ->
                    LensOptionUi(
                        key = lens.label,
                        label = UiText.Plain(lens.label),
                        isSelected = lens == snapshot.cameraInteraction.selectedPhysicalLens,
                    )
                },
                stabilization = StabilizationUiState(
                    isSupported = snapshot.cameraInteraction.isStabilizationSupported,
                    isEnabled = snapshot.cameraInteraction.isStabilizationEnabled,
                ),
            ),
            settings = SettingsUiState(
                connection = connectionState,
                codecOptions = CodecPreference.entries.map { pref ->
                    SelectOptionUi(
                        key = pref.name,
                        label = mapCodecPreferenceLabel(pref),
                        isSelected = pref == snapshot.codecPreference,
                    )
                },
                profileOptions = VideoProfiles.all.map { profile ->
                    SelectOptionUi(
                        key = profile.id,
                        label = mapVideoProfileLabel(profile),
                        isSelected = profile.id == snapshot.profile.id,
                    )
                },
                receiverName = snapshot.activeReceiverName?.let(UiText::Plain),
                connectionStatus = mapConnectionStatusText(snapshot.streamState),
            ),
            isScreenDimmed = snapshot.isScreenDimmed,
            isZoomTrayOpen = snapshot.isZoomTrayOpen,
            dialog = mapDialog(snapshot),
            validationMessage = snapshot.validationMessage?.let(UiText::Plain),
            failureDiagnostics = (snapshot.streamState as? StreamState.Failed)?.let { failed ->
                buildFailureDiagnostics(
                    receiverName = snapshot.activeReceiverName,
                    profile = snapshot.profile,
                    codecPreference = snapshot.codecPreference,
                    failure = failed.failure,
                    cause = failed.failure.causeOrNull(),
                )
            },
            cameraPermissionGranted = snapshot.cameraPermissionGranted,
        )
    }

    private fun mapConnection(
        streamState: StreamState,
        activeReceiverName: String?,
    ): ConnectionUiState = when (streamState) {
        StreamState.Idle -> ConnectionUiState.Waiting
        StreamState.CheckingReceiver -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.checking_receiver),
        )
        StreamState.Negotiating -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.negotiating_codec),
        )
        is StreamState.Preparing -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.preparing_stream, listOf(mapCodecName(streamState.codec))),
        )
        is StreamState.Starting -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.starting_stream),
        )
        is StreamState.Streaming -> ConnectionUiState.Streaming(
            codec = UiText.Resource(
                R.string.streaming_codec,
                listOf(mapCodecName(streamState.session.selectedCodec)),
            ),
            receiverName = activeReceiverName?.let(UiText::Plain),
            profile = mapVideoProfileLabel(streamState.session.profile),
        )
        StreamState.Stopping -> ConnectionUiState.Stopping
        is StreamState.Failed -> ConnectionUiState.Failed(
            UiText.Plain(failureMessage(streamState.failure)),
        )
    }

    private fun mapDialog(snapshot: SenderDomainSnapshot): SenderDialogUiState? = when {
        snapshot.isPermissionDialogOpen -> SenderDialogUiState.CameraPermission(
            title = UiText.Resource(R.string.camera_permission_title),
            message = UiText.Resource(R.string.camera_permission_message),
        )
        else -> null
    }

    private fun mapCodecPreferenceLabel(preference: CodecPreference): UiText = when (preference) {
        CodecPreference.AUTO_PREFER_H265 -> UiText.Resource(R.string.codec_auto_prefer_h265)
        CodecPreference.FORCE_H264 -> UiText.Resource(R.string.codec_h264)
        CodecPreference.FORCE_H265 -> UiText.Resource(R.string.codec_h265)
    }

    private fun mapVideoProfileLabel(profile: VideoProfile): UiText = when (profile.id) {
        VideoProfiles.PROFILE_1080P30.id -> UiText.Resource(R.string.profile_1080p30)
        VideoProfiles.PROFILE_1440P30.id -> UiText.Resource(R.string.profile_1440p30)
        VideoProfiles.PROFILE_4K30.id -> UiText.Resource(R.string.profile_4k30)
        else -> UiText.Plain(profile.id)
    }

    private fun mapConnectionStatusText(streamState: StreamState): UiText? = when (streamState) {
        StreamState.Idle -> UiText.Resource(R.string.not_connected)
        StreamState.CheckingReceiver -> UiText.Resource(R.string.checking_receiver)
        StreamState.Negotiating -> UiText.Resource(R.string.negotiating_codec)
        is StreamState.Preparing -> UiText.Resource(
            R.string.preparing_stream,
            listOf(mapCodecName(streamState.codec)),
        )
        is StreamState.Starting -> UiText.Resource(R.string.starting_stream)
        is StreamState.Streaming -> UiText.Resource(
            R.string.streaming_codec,
            listOf(mapCodecName(streamState.session.selectedCodec)),
        )
        StreamState.Stopping -> UiText.Resource(R.string.stopping_stream)
        is StreamState.Failed -> UiText.Plain(failureMessage(streamState.failure))
    }

    private fun mapCodecName(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> "H.264"
        VideoCodec.H265 -> "H.265"
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

    private fun StreamFailure.causeOrNull(): Throwable? = when (this) {
        is StreamFailure.EncoderPreparationFailed -> cause
        is StreamFailure.StreamStartFailed -> cause
        is StreamFailure.Unexpected -> cause
        else -> null
    }
}
