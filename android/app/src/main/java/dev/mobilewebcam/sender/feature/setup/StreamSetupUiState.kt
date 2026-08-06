package dev.mobilewebcam.sender.feature.setup

import androidx.compose.runtime.Immutable
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.VideoProfile

@Immutable
data class StreamSetupUiState(
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val receiverName: UiText? = null,
    val profileOptions: List<SelectOptionUi> = emptyList(),
    val orientationOptions: List<SelectOptionUi> = emptyList(),
    val selectedProfile: VideoProfile,
    val selectedOrientation: StreamOrientation,
    val validationMessage: UiText? = null,
) {
    val canStart: Boolean
        get() = connection is ConnectionUiState.Waiting || connection is ConnectionUiState.Failed
}
