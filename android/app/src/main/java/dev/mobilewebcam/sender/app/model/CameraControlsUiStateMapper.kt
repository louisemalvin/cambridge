package dev.mobilewebcam.sender.app.model

import dev.mobilewebcam.sender.media.camera.CameraInteractionState

object CameraControlsUiStateMapper {
    fun map(cameraInteraction: CameraInteractionState): CameraControlsUiState =
        CameraControlsUiState(
            zoom = ZoomUiState(
                ratio = cameraInteraction.zoomRatio,
                minimumRatio = cameraInteraction.minZoomRatio,
                maximumRatio = cameraInteraction.maxZoomRatio,
                isCameraActive = cameraInteraction.isCameraActive,
            ),
            lensOptions = cameraInteraction.physicalLensOptions.map { lens ->
                LensOptionUi(
                    key = lens.label,
                    label = UiText.Plain(lens.label),
                    isSelected = lens == cameraInteraction.selectedPhysicalLens,
                )
            },
            stabilization = StabilizationUiState(
                isSupported = cameraInteraction.isStabilizationSupported,
                isEnabled = cameraInteraction.isStabilizationEnabled,
            ),
        )
}
