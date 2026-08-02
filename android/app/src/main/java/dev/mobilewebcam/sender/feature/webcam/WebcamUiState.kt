package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.PreviewUiState

data class WebcamUiState(
    val preview: PreviewUiState = PreviewUiState(),
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val camera: CameraControlsUiState = CameraControlsUiState(),
    val isScreenDimmed: Boolean = false,
    val isZoomTrayOpen: Boolean = false,
    val cameraPermissionGranted: Boolean = true,
    val isPermissionDialogOpen: Boolean = false,
    val failureDiagnostics: String? = null,
)
