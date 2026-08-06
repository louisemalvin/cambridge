package dev.mobilewebcam.sender.feature.webcam

import androidx.compose.runtime.Immutable
import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.PreviewUiState
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.UiText

@Immutable
data class WebcamUiState(
    val preview: PreviewUiState = PreviewUiState(),
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val sessionOrientation: UiText? = null,
    val camera: CameraControlsUiState = CameraControlsUiState(),
    val dialog: SenderDialogUiState? = null,
    val cameraPermissionGranted: Boolean = false,
    val validationMessage: UiText? = null,
    val failureDiagnostics: String? = null,
    val isScreenDimmed: Boolean = false,
    val isZoomTrayOpen: Boolean = false,
)
