package dev.mobilewebcam.sender.ui

import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.camera.CameraInteractionState
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.discovery.PendingApproval
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.ui.model.CameraControlsUiState
import dev.mobilewebcam.sender.ui.model.ConnectionUiState
import dev.mobilewebcam.sender.ui.model.LensOptionUi
import dev.mobilewebcam.sender.ui.model.PreviewUiState
import dev.mobilewebcam.sender.ui.model.SelectOptionUi
import dev.mobilewebcam.sender.ui.model.SenderDialogUiState
import dev.mobilewebcam.sender.ui.model.SenderScreenState
import dev.mobilewebcam.sender.ui.model.SettingsUiState
import dev.mobilewebcam.sender.ui.model.StabilizationUiState
import dev.mobilewebcam.sender.ui.model.UiText
import dev.mobilewebcam.sender.ui.model.ZoomUiState

internal data class SenderDomainSnapshot(
    val codecPreference: CodecPreference,
    val profile: VideoProfile,
    val cameraInteraction: CameraInteractionState,
    val streamState: StreamState,
    val cameraPermissionGranted: Boolean,
    val pendingApproval: PendingApproval?,
    val activeReceiverName: String?,
    val validationMessage: String?,
    val isScreenDimmed: Boolean,
    val isSettingsOpen: Boolean,
    val isZoomTrayOpen: Boolean,
    val isPermissionDialogOpen: Boolean,
)

internal object SenderScreenStateMapper {
    fun map(snapshot: SenderDomainSnapshot): SenderScreenState {
        val previewProfile = previewProfile(snapshot.profile, snapshot.streamState)
        val connection = connectionState(snapshot.streamState, snapshot.activeReceiverName)
        val failure = (snapshot.streamState as? StreamState.Failed)?.failure
        val failureDiagnostics = failure?.let { streamFailure ->
            buildFailureDiagnostics(
                receiverName = snapshot.activeReceiverName,
                profile = snapshot.profile,
                codecPreference = snapshot.codecPreference,
                failure = streamFailure,
                cause = streamFailure.causeOrNull(),
            )
        }

        return SenderScreenState(
            preview = PreviewUiState(
                landscapeAspectRatio = previewProfile.width.toFloat() / previewProfile.height,
                isLive = snapshot.streamState.isPreviewActive(),
            ),
            connection = connection,
            camera = snapshot.cameraInteraction.toUiState(),
            settings = SettingsUiState(
                codecOptions = codecOptions(snapshot.codecPreference),
                profileOptions = profileOptions(snapshot.profile),
                receiverName = snapshot.activeReceiverName?.let(UiText::Plain),
                connectionStatus = connectionStatus(connection),
            ),
            dialog = when {
                snapshot.pendingApproval != null -> SenderDialogUiState.PendingApproval(
                    receiverName = UiText.Plain(snapshot.pendingApproval.receiverName),
                )
                snapshot.isPermissionDialogOpen && !snapshot.cameraPermissionGranted ->
                    SenderDialogUiState.CameraPermission(
                        title = UiText.Resource(R.string.camera_permission_title),
                        message = UiText.Resource(R.string.camera_permission_message),
                    )
                else -> null
            },
            cameraPermissionGranted = snapshot.cameraPermissionGranted,
            validationMessage = snapshot.validationMessage?.let(UiText::Plain),
            failureDiagnostics = failureDiagnostics,
            isScreenDimmed = snapshot.isScreenDimmed,
            isSettingsOpen = snapshot.isSettingsOpen,
            isZoomTrayOpen = snapshot.isZoomTrayOpen,
        )
    }

    private fun connectionState(
        state: StreamState,
        receiverName: String?,
    ): ConnectionUiState = when (state) {
        StreamState.Idle -> ConnectionUiState.Waiting
        StreamState.CheckingReceiver -> connecting(R.string.checking_receiver)
        StreamState.Negotiating -> connecting(R.string.negotiating_codec)
        is StreamState.Preparing -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.preparing_stream, listOf(state.codec.protocolId)),
        )
        is StreamState.Starting -> ConnectionUiState.Connecting(
            UiText.Resource(R.string.starting_stream),
        )
        is StreamState.Streaming -> ConnectionUiState.Streaming(
            receiverName = (receiverName ?: state.session.endpoint.host).let(UiText::Plain),
            codec = UiText.Resource(
                R.string.streaming_codec,
                listOf(state.session.selectedCodec.protocolId),
            ),
            profile = profileLabel(state.session.profile),
        )
        StreamState.Stopping -> ConnectionUiState.Stopping
        is StreamState.Failed -> ConnectionUiState.Failed(
            message = UiText.Plain(failureMessage(state.failure)),
        )
    }

    private fun connecting(resourceId: Int): ConnectionUiState.Connecting =
        ConnectionUiState.Connecting(UiText.Resource(resourceId))

    private fun connectionStatus(state: ConnectionUiState): UiText = when (state) {
        ConnectionUiState.Waiting -> UiText.Resource(R.string.waiting_for_connection)
        is ConnectionUiState.Connecting -> state.status
        is ConnectionUiState.Streaming -> UiText.Resource(
            R.string.connected_to_receiver,
            listOf(state.receiverName?.plainValue() ?: "receiver"),
        )
        ConnectionUiState.Stopping -> UiText.Resource(R.string.stopping_stream)
        is ConnectionUiState.Failed -> state.message
    }

    private fun previewProfile(profile: VideoProfile, state: StreamState): VideoProfile = when (state) {
        is StreamState.Preparing -> state.profile
        is StreamState.Starting -> state.session.profile
        is StreamState.Streaming -> state.session.profile
        else -> profile
    }

    private fun CameraInteractionState.toUiState(): CameraControlsUiState =
        CameraControlsUiState(
            zoom = ZoomUiState(
                ratio = zoomRatio,
                minimumRatio = minZoomRatio,
                maximumRatio = maxZoomRatio,
                isCameraActive = isCameraActive,
            ),
            lensOptions = physicalLensOptions.map { lens ->
                LensOptionUi(
                    key = lens.label,
                    label = UiText.Plain(lens.label),
                    isSelected = lens == selectedPhysicalLens,
                )
            },
            stabilization = StabilizationUiState(
                isSupported = isStabilizationSupported,
                isEnabled = isStabilizationEnabled,
            ),
        )

    private fun codecOptions(selected: CodecPreference): List<SelectOptionUi> =
        CodecPreference.entries.map { preference ->
            SelectOptionUi(
                key = preference.name,
                label = codecLabel(preference),
                isSelected = preference == selected,
            )
        }

    private fun profileOptions(selected: VideoProfile): List<SelectOptionUi> =
        VideoProfiles.all.map { profile ->
            SelectOptionUi(
                key = profile.id,
                label = profileLabel(profile),
                isSelected = profile.id == selected.id,
            )
        }

    private fun codecLabel(preference: CodecPreference): UiText = when (preference) {
        CodecPreference.AUTO_PREFER_H265 -> UiText.Resource(R.string.codec_auto_prefer_h265)
        CodecPreference.FORCE_H264 -> UiText.Resource(R.string.codec_h264)
        CodecPreference.FORCE_H265 -> UiText.Resource(R.string.codec_h265)
    }

    private fun profileLabel(profile: VideoProfile): UiText = when (profile.id) {
        "1080p30" -> UiText.Resource(R.string.profile_1080p30)
        "1440p30" -> UiText.Resource(R.string.profile_1440p30)
        "4k30" -> UiText.Resource(R.string.profile_4k30)
        else -> UiText.Plain("${profile.width} x ${profile.height} @ ${profile.fps} FPS")
    }

    internal fun failureMessage(failure: StreamFailure): String = when (failure) {
        StreamFailure.CameraPermissionDenied -> "Camera permission is required"
        StreamFailure.CameraUnavailable -> "Camera is unavailable"
        is StreamFailure.ReceiverUnavailable -> failure.reason
        is StreamFailure.NoCompatibleCodec -> "No compatible codec supports this profile"
        is StreamFailure.ForcedCodecUnsupported ->
            "${failure.codec.protocolId} is not available for this profile"
        is StreamFailure.ReceiverRejectedProfile -> failure.reason
        is StreamFailure.EncoderPreparationFailed ->
            "${failure.codec.protocolId} encoder preparation failed"
        is StreamFailure.StreamStartFailed -> "The media stream could not start"
        StreamFailure.NetworkDisconnected -> "The network connection was interrupted"
        is StreamFailure.Unexpected -> "An unexpected streaming error occurred"
    }

    private fun StreamState.isPreviewActive(): Boolean = when (this) {
        is StreamState.Preparing,
        is StreamState.Starting,
        is StreamState.Streaming,
        StreamState.Stopping -> true
        else -> false
    }

    private fun UiText.plainValue(): String = when (this) {
        is UiText.Plain -> value
        is UiText.Resource -> "receiver"
    }

    private fun StreamFailure.causeOrNull(): Throwable? = when (this) {
        is StreamFailure.EncoderPreparationFailed -> cause
        is StreamFailure.StreamStartFailed -> cause
        is StreamFailure.Unexpected -> cause
        else -> null
    }
}
