package dev.mobilewebcam.sender.ui.model

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
