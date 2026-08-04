package dev.mobilewebcam.sender.feature.settings

import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.app.model.UiText

data class SettingsUiState(
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val codecOptions: List<SelectOptionUi> = emptyList(),
    val profileOptions: List<SelectOptionUi> = emptyList(),
    val receiverName: UiText? = null,
    val connectionStatus: UiText? = null,
    val hasConfiguredReceiver: Boolean = false,
    val camera: CameraControlsUiState = CameraControlsUiState(),
    val validationMessage: UiText? = null,
    val failureDiagnostics: String? = null,
    val isStreaming: Boolean = false,
)
