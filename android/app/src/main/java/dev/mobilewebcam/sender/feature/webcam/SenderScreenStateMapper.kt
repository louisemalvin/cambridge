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
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.PendingApproval
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
    val pendingApproval: PendingApproval?,
    val activeReceiverName: String?,
    val validationMessage: String?,
    val isScreenDimmed: Boolean,
    val isZoomTrayOpen: Boolean,
    val isPermissionDialogOpen: Boolean,
)

object SenderScreenStateMapper {
    fun map(snapshot: SenderDomainSnapshot): SenderScreenState {
        val connectionState = mapConnection(snapshot.streamState, snapshot.activeReceiverName)
        val dialogState = mapDialog(snapshot)

        return SenderScreenState(
            preview = PreviewUiState(
                landscapeAspectRatio = snapshot.profile.aspectRatio,
            ),
            connection = connectionState,
            camera = CameraControlsUiState(
                zoom = ZoomUiState(
                    isSupported = snapshot.cameraInteraction.zoom.isSupported,
                    ratio = snapshot.cameraInteraction.zoom.ratio,
                    minimumRatio = snapshot.cameraInteraction.zoom.minimumRatio,
                    maximumRatio = snapshot.cameraInteraction.zoom.maximumRatio,
                    isCameraActive = snapshot.streamState !is StreamState.Idle,
                ),
                lensOptions = snapshot.cameraInteraction.physicalLensOptions.map { lens ->
                    LensOptionUi(
                        key = lens.label,
                        label = lens.label,
                        isSelected = lens == snapshot.cameraInteraction.selectedLens,
                    )
                },
                stabilization = StabilizationUiState(
                    isSupported = snapshot.cameraInteraction.isStabilizationSupported,
                    isEnabled = snapshot.cameraInteraction.isStabilizationEnabled,
                ),
            ),
            settings = SettingsUiState(
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
            dialog = dialogState,
            validationMessage = snapshot.validationMessage?.let(UiText::Plain),
            failureDiagnostics = (snapshot.streamState as? StreamState.Failed)?.failure?.details,
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
        StreamState.NegotiatingCodec -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.negotiating_codec),
        )
        is StreamState.Preparing -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.preparing_stream, listOf(mapCodecName(streamState.codec))),
        )
        is StreamState.Starting -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.starting_stream),
        )
        is StreamState.Streaming -> ConnectionUiState.Streaming(
            codecLabel = UiText.Resource(
                R.string.streaming_codec,
                listOf(mapCodecName(streamState.codec)),
            ),
            receiverName = activeReceiverName?.let(UiText::Plain),
        )
        StreamState.Stopping -> ConnectionUiState.Stopping
        is StreamState.Failed -> ConnectionUiState.Failed(
            UiText.Plain(failureMessage(streamState.failure)),
        )
    }

    private fun mapDialog(snapshot: SenderDomainSnapshot): SenderDialogUiState? = when {
        snapshot.pendingApproval != null -> SenderDialogUiState.PendingApproval(
            UiText.Plain(snapshot.pendingApproval.receiverName),
        )
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
        StreamState.NegotiatingCodec -> UiText.Resource(R.string.negotiating_codec)
        is StreamState.Preparing -> UiText.Resource(
            R.string.preparing_stream,
            listOf(mapCodecName(streamState.codec)),
        )
        is StreamState.Starting -> UiText.Resource(R.string.starting_stream)
        is StreamState.Streaming -> UiText.Resource(
            R.string.streaming_codec,
            listOf(mapCodecName(streamState.codec)),
        )
        StreamState.Stopping -> UiText.Resource(R.string.stopping_stream)
        is StreamState.Failed -> UiText.Plain(failureMessage(streamState.failure))
    }

    private fun mapCodecName(codec: VideoCodec): String = when (codec) {
        VideoCodec.H264 -> "H.264"
        VideoCodec.H265 -> "H.265"
    }

    fun failureMessage(failure: StreamFailure): String = when (failure) {
        is StreamFailure.CapabilitiesNegotiationFailed -> failure.reason
        is StreamFailure.CodecUnsupported -> "Codec ${failure.codec} is unsupported"
        is StreamFailure.ConfigurationInvalid -> failure.reason
        is StreamFailure.NetworkError -> failure.reason
        is StreamFailure.PermissionDenied -> failure.permission
        is StreamFailure.PipelineError -> failure.reason
        is StreamFailure.ReceiverRejected -> failure.reason
        is StreamFailure.SessionNegotiationFailed -> failure.reason
    }
}
