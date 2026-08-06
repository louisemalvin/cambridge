package dev.mobilewebcam.sender.feature.setup

import androidx.compose.runtime.Immutable
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.AntiFlickerUiState
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.app.model.StabilizationUiState
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.VideoProfile

@Immutable
sealed interface ReceiverReadinessUiState {
    data object Checking : ReceiverReadinessUiState

    data class Ready(
        val receiverName: UiText,
        val address: UiText,
    ) : ReceiverReadinessUiState

    data class Unavailable(
        val message: UiText,
    ) : ReceiverReadinessUiState
}

@Immutable
data class StreamSetupUiState(
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val receiverReadiness: ReceiverReadinessUiState = ReceiverReadinessUiState.Checking,
    val receiverName: UiText? = null,
    val profileOptions: List<SelectOptionUi> = emptyList(),
    val frameRateOptions: List<SelectOptionUi> = emptyList(),
    val orientationOptions: List<SelectOptionUi> = emptyList(),
    val stabilization: StabilizationUiState = StabilizationUiState(),
    val antiFlicker: AntiFlickerUiState = AntiFlickerUiState(),
    val selectedProfile: VideoProfile,
    val selectedOrientation: StreamOrientation,
    val selectedProfileSupported: Boolean = false,
    val validationMessage: UiText? = null,
) {
    val canStart: Boolean
        get() = (connection is ConnectionUiState.Waiting || connection is ConnectionUiState.Failed) &&
            receiverReadiness is ReceiverReadinessUiState.Ready &&
            selectedProfileSupported
}
