package dev.mobilewebcam.sender.feature.settings

import androidx.compose.runtime.Immutable
import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.UiText

@Immutable
data class SettingsUiState(
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val sessionOrientation: UiText? = null,
    val receiverName: UiText? = null,
    val connectionStatus: UiText? = null,
    val hasConfiguredReceiver: Boolean = false,
    val camera: CameraControlsUiState = CameraControlsUiState(),
    val validationMessage: UiText? = null,
    val failureDiagnostics: String? = null,
    val isStreaming: Boolean = false,
)
