package dev.cambridge.sender.feature.setup

import androidx.compose.runtime.Immutable
import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.AntiFlickerUiState
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.StabilizationUiState
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.VideoProfile

@Immutable
sealed interface ReceiverReadinessUiState {
    data object Checking : ReceiverReadinessUiState

    data object SelectionRequired : ReceiverReadinessUiState

    data class Ready(
        val receiverName: UiText,
        val address: UiText,
    ) : ReceiverReadinessUiState

    data class Unavailable(
        val message: UiText,
    ) : ReceiverReadinessUiState
}

@Immutable
data class CameraPermissionUiState(
    val isGranted: Boolean = true,
    val isPermanentlyDenied: Boolean = false,
)

@Immutable
data class StreamSetupUiState(
    val connection: ConnectionUiState = ConnectionUiState.Waiting,
    val receiverReadiness: ReceiverReadinessUiState = ReceiverReadinessUiState.Checking,
    val manualReceiverHost: String = "",
    val manualReceiverHostError: UiText? = null,
    val receiverOptions: List<SelectOptionUi> = emptyList(),
    val isManualReceiverInputVisible: Boolean = false,
    val resolutionOptions: List<SelectOptionUi> = emptyList(),
    val frameRateOptions: List<SelectOptionUi> = emptyList(),
    val orientationOptions: List<SelectOptionUi> = emptyList(),
    val bitrate: BitrateUiState = BitrateUiState(),
    val stabilization: StabilizationUiState = StabilizationUiState(),
    val antiFlicker: AntiFlickerUiState = AntiFlickerUiState(),
    val selectedProfile: VideoProfile,
    val selectedOrientation: StreamOrientation,
    val selectedProfileSupported: Boolean = false,
    val videoCapabilitiesReady: Boolean = false,
    val validationMessage: UiText? = null,
) {
    val canStart: Boolean
        get() = (connection is ConnectionUiState.Waiting || connection is ConnectionUiState.Failed) &&
            receiverReadiness is ReceiverReadinessUiState.Ready &&
            selectedProfileSupported &&
            bitrate.isAvailable
}

@Immutable
data class BitrateUiState(
    val isAvailable: Boolean = false,
    val selectedBps: Int = EMPTY_BITRATE_BPS,
    val minimumBps: Int = EMPTY_BITRATE_BPS,
    val maximumBps: Int = EMPTY_BITRATE_BPS,
    val stepBps: Int = EMPTY_BITRATE_BPS,
) {
    val lastIndex: Int
        get() = if (isAvailable && stepBps > EMPTY_BITRATE_BPS) {
            (maximumBps - minimumBps) / stepBps
        } else {
            EMPTY_SLIDER_INDEX
        }

    val selectedIndex: Int
        get() = if (isAvailable && stepBps > EMPTY_BITRATE_BPS) {
            (selectedBps - minimumBps) / stepBps
        } else {
            EMPTY_SLIDER_INDEX
        }

    val sliderSteps: Int
        get() = (lastIndex - SLIDER_ENDPOINT_COUNT).coerceAtLeast(EMPTY_SLIDER_INDEX)
}

private const val EMPTY_BITRATE_BPS = 0
private const val EMPTY_SLIDER_INDEX = 0
private const val SLIDER_ENDPOINT_COUNT = 1
