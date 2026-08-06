package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.CameraControlsUiStateMapper
import dev.mobilewebcam.sender.app.model.PreviewUiState
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.StreamPresentationMapper
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.buildFailureDiagnostics
import dev.mobilewebcam.sender.model.StreamState

object WebcamUiStateMapper {
    fun map(
        snapshot: StreamPresentationSnapshot,
        cameraPermissionGranted: Boolean,
        isScreenDimmed: Boolean,
        isZoomTrayOpen: Boolean,
        isPermissionDialogOpen: Boolean,
    ): WebcamUiState {
        val connection = StreamPresentationMapper.connection(snapshot)
        return WebcamUiState(
            preview = PreviewUiState(
                landscapeAspectRatio = snapshot.profile.width.toFloat() / snapshot.profile.height,
                isLive = snapshot.streamState is StreamState.Streaming ||
                    snapshot.streamState == StreamState.Stopping,
            ),
            connection = connection,
            sessionOrientation = StreamPresentationMapper.sessionOrientation(snapshot.streamState),
            camera = CameraControlsUiStateMapper.map(snapshot.cameraInteraction),
            dialog = if (isPermissionDialogOpen) {
                SenderDialogUiState.CameraPermission(
                    title = UiText.Resource(R.string.camera_permission_title),
                    message = UiText.Resource(R.string.camera_permission_message),
                )
            } else {
                null
            },
            cameraPermissionGranted = cameraPermissionGranted,
            validationMessage = snapshot.validationMessage?.let(UiText::Plain),
            failureDiagnostics = (snapshot.streamState as? StreamState.Failed)?.let { failed ->
                buildFailureDiagnostics(
                    receiverName = snapshot.activeReceiverName,
                    profile = snapshot.profile,
                    failure = failed.failure,
                    cause = StreamPresentationMapper.causeOrNull(failed.failure),
                )
            },
            isScreenDimmed = isScreenDimmed,
            isZoomTrayOpen = isZoomTrayOpen,
        )
    }
}
