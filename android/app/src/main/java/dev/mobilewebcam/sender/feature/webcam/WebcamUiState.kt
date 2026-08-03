package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.PreviewUiState
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.UiText

data class WebcamUiState(
    val preview: PreviewUiState = PreviewUiState(),
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val camera: CameraControlsUiState = CameraControlsUiState(),
    val dialog: SenderDialogUiState? = null,
    val cameraPermissionGranted: Boolean = false,
    val validationMessage: UiText? = null,
    val failureDiagnostics: String? = null,
    val isScreenDimmed: Boolean = false,
    val isZoomTrayOpen: Boolean = false,
)
