package dev.cambridge.sender.app.model

import androidx.compose.runtime.Immutable
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.media.camera.CameraZoom
import dev.cambridge.sender.media.camera.CameraStabilizationApplyStatus
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.model.StreamOrientation

@Immutable
data class PreviewUiState(
    val landscapeAspectRatio: Float = DEFAULT_PREVIEW_ASPECT_RATIO,
    val isLive: Boolean = false,
) {
    init {
        require(landscapeAspectRatio.isFinite() && landscapeAspectRatio > POSITIVE_FLOAT_BOUND) {
            "Preview aspect ratio must be positive and finite"
        }
    }
}

enum class PreviewOrientation(
    val isPortrait: Boolean,
) {
    PORTRAIT(isPortrait = true),
    LANDSCAPE(isPortrait = false),
}

@Immutable
sealed interface ConnectionUiState {
    data object Waiting : ConnectionUiState

    data class Connecting(val status: UiText) : ConnectionUiState

    data class Streaming(
        val receiverName: UiText?,
        val status: UiText,
        val profile: UiText,
    ) : ConnectionUiState

    data object Stopping : ConnectionUiState

    data class Failed(val message: UiText) : ConnectionUiState
}

@Immutable
data class CameraControlsUiState(
    val zoom: ZoomUiState = ZoomUiState(),
    val lensOptions: List<LensOptionUi> = emptyList(),
    val stabilization: StabilizationUiState = StabilizationUiState(),
    val antiFlicker: AntiFlickerUiState = AntiFlickerUiState(),
)

@Immutable
data class SelectOptionUi(
    val key: String,
    val label: UiText,
    val isSelected: Boolean,
    val isEnabled: Boolean = true,
    val disabledReason: UiText? = null,
)

@Immutable
data class ZoomUiState(
    val ratio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val minimumRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val maximumRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val isCameraActive: Boolean = false,
) {
    init {
        require(minimumRatio > POSITIVE_FLOAT_BOUND) {
            "Minimum zoom ratio must be positive"
        }
        require(maximumRatio >= minimumRatio) {
            "Maximum zoom ratio must not be below minimum"
        }
        require(ratio.isFinite() && ratio in minimumRatio..maximumRatio) {
            "Zoom ratio must be finite and within the camera range"
        }
    }

    val isSupported: Boolean
        get() = maximumRatio > minimumRatio
}

@Immutable
data class LensOptionUi(
    val key: String,
    val label: UiText,
    val isSelected: Boolean,
)

@Immutable
data class StabilizationUiState(
    val supportedModes: List<CameraStabilizationMode> = listOf(CameraStabilizationMode.OFF),
    val requestedMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
    val selectedMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
    val applyStatus: CameraStabilizationApplyStatus = CameraStabilizationApplyStatus.IDLE,
    val appliedMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
)

@Immutable
data class AntiFlickerUiState(
    val options: List<SelectOptionUi> = emptyList(),
)

sealed interface SenderScreenAction {
    data object ToggleScreenDimmed : SenderScreenAction
    data object OpenSettings : SenderScreenAction
    data object CloseSettings : SenderScreenAction
    data object ToggleZoomTray : SenderScreenAction
    data object CloseZoomTray : SenderScreenAction
    data class ZoomChanged(val ratio: Float) : SenderScreenAction
    data object ResetZoom : SenderScreenAction
    data class LensSelected(val key: String) : SenderScreenAction
    data class StabilizationModeChanged(
        val mode: dev.cambridge.sender.media.camera.CameraStabilizationMode,
    ) : SenderScreenAction
    data class AntiFlickerChanged(val mode: AntiFlickerMode) : SenderScreenAction
    data class ProfileSelected(val profileId: String) : SenderScreenAction
    data class FrameRateSelected(val fps: Int) : SenderScreenAction
    data class BitrateSelected(val bitrateBps: Int) : SenderScreenAction
    data class StreamOrientationSelected(val orientation: StreamOrientation) : SenderScreenAction
    data class ReceiverNameChanged(val name: String) : SenderScreenAction
    data class ReceiverHostChanged(val host: String) : SenderScreenAction
    data class ReceiverSelected(val receiverId: String) : SenderScreenAction
    data object ShowManualReceiverInput : SenderScreenAction
    data object HideManualReceiverInput : SenderScreenAction
    data class ReceiverControlPortChanged(val port: String) : SenderScreenAction
    data object UseManualReceiverHost : SenderScreenAction
    data object CheckReceiver : SenderScreenAction
    data object OpenStreamSetup : SenderScreenAction
    data object StartStream : SenderScreenAction
    data object RequestStopStream : SenderScreenAction
    data object CopyDiagnostics : SenderScreenAction
}

sealed interface SenderUiEffect {
    data class CopyDiagnostics(val details: String) : SenderUiEffect

}

private const val DEFAULT_PREVIEW_ASPECT_RATIO = 16.0f / 9.0f
private const val POSITIVE_FLOAT_BOUND = 0.0f
