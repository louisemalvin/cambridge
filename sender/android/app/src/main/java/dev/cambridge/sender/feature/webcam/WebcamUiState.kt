package dev.cambridge.sender.feature.webcam

import androidx.compose.runtime.Immutable
import dev.cambridge.sender.app.model.CameraControlsUiState
import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.PreviewUiState
import dev.cambridge.sender.app.model.SenderDialogUiState
import dev.cambridge.sender.app.model.UiText

@Immutable
data class WebcamUiState(
    val preview: PreviewUiState = PreviewUiState(),
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val sessionOrientation: UiText? = null,
    val camera: CameraControlsUiState = CameraControlsUiState(),
    val dialog: SenderDialogUiState? = null,
    val cameraPermissionGranted: Boolean = false,
    val cameraPermissionPermanentlyDenied: Boolean = false,
    val validationMessage: UiText? = null,
    val failureDiagnostics: String? = null,
    val isScreenDimmed: Boolean = false,
    val isZoomTrayOpen: Boolean = false,
)
