package dev.cambridge.sender.feature.settings

import androidx.compose.runtime.Immutable
import dev.cambridge.sender.app.model.CameraControlsUiState
import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.UiText

@Immutable
data class SettingsUiState(
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val sessionOrientation: UiText? = null,
    val receiverName: UiText? = null,
    val connectionStatus: UiText? = null,
    val camera: CameraControlsUiState = CameraControlsUiState(),
    val validationMessage: UiText? = null,
    val failureDiagnostics: String? = null,
    val isStreaming: Boolean = false,
)
