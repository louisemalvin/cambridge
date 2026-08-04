package dev.mobilewebcam.sender.app.model

import dev.mobilewebcam.sender.media.camera.CameraZoom
import dev.mobilewebcam.sender.model.ReceiverEndpoint

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

sealed interface ConnectionUiState {
    data object Waiting : ConnectionUiState

    data class Connecting(val status: UiText) : ConnectionUiState

    data class Streaming(
        val receiverName: UiText?,
        val codec: UiText,
        val profile: UiText,
    ) : ConnectionUiState

    data object Stopping : ConnectionUiState

    data class Failed(val message: UiText) : ConnectionUiState
}

data class CameraControlsUiState(
    val zoom: ZoomUiState = ZoomUiState(),
    val lensOptions: List<LensOptionUi> = emptyList(),
    val stabilization: StabilizationUiState = StabilizationUiState(),
)

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

data class LensOptionUi(
    val key: String,
    val label: UiText,
    val isSelected: Boolean,
)

data class StabilizationUiState(
    val isSupported: Boolean = false,
    val isEnabled: Boolean = false,
)

data class SelectOptionUi(
    val key: String,
    val label: UiText,
    val isSelected: Boolean,
)

sealed interface SenderDialogUiState {
    data class CameraPermission(
        val title: UiText,
        val message: UiText,
    ) : SenderDialogUiState
}

sealed interface SenderScreenAction {
    data object ToggleScreenDimmed : SenderScreenAction
    data object OpenSettings : SenderScreenAction
    data object CloseSettings : SenderScreenAction
    data object ToggleZoomTray : SenderScreenAction
    data object CloseZoomTray : SenderScreenAction
    data class ZoomChanged(val ratio: Float) : SenderScreenAction
    data object ResetZoom : SenderScreenAction
    data class LensSelected(val key: String) : SenderScreenAction
    data class StabilizationChanged(val enabled: Boolean) : SenderScreenAction
    data class CodecSelected(val key: String) : SenderScreenAction
    data class ProfileSelected(val key: String) : SenderScreenAction
    data object OpenPermissionDialog : SenderScreenAction
    data object DismissPermissionDialog : SenderScreenAction
    data object RequestCameraPermission : SenderScreenAction
    data class ReceiverNameChanged(val name: String) : SenderScreenAction
    data class ReceiverHostChanged(val host: String) : SenderScreenAction
    data class ReceiverControlPortChanged(val port: String) : SenderScreenAction
    data class ReceiverTokenChanged(val token: String) : SenderScreenAction
    data class DiscoveredReceiverSelected(val endpoint: ReceiverEndpoint) : SenderScreenAction
    data object ConnectReceiver : SenderScreenAction
    data object StopStream : SenderScreenAction
    data object ForgetReceiver : SenderScreenAction
    data object CopyDiagnostics : SenderScreenAction
}

sealed interface SenderUiEffect {
    data object RequestCameraPermission : SenderUiEffect

    data class CopyDiagnostics(val details: String) : SenderUiEffect

    data object NavigateToPairing : SenderUiEffect
}

private const val DEFAULT_PREVIEW_ASPECT_RATIO = 16.0f / 9.0f
private const val POSITIVE_FLOAT_BOUND = 0.0f
