package dev.cambridge.sender.app.model

import dev.cambridge.sender.R
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.media.camera.CameraInteractionState

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
                supportedModes = cameraInteraction.stabilization.supportedModes,
                requestedMode = cameraInteraction.stabilization.requestedMode,
                selectedMode = cameraInteraction.stabilization.selectedMode,
                applyStatus = cameraInteraction.stabilization.applyStatus,
                appliedMode = cameraInteraction.stabilization.appliedMode,
            ),
            antiFlicker = AntiFlickerUiState(
                options = cameraInteraction.supportedAntiFlickerModes.map { mode ->
                    SelectOptionUi(
                        key = mode.name,
                        label = UiText.Resource(antiFlickerLabel(mode)),
                        isSelected = mode == cameraInteraction.antiFlickerMode,
                    )
                },
            ),
        )

    private fun antiFlickerLabel(mode: AntiFlickerMode): Int = when (mode) {
        AntiFlickerMode.AUTO -> R.string.anti_flicker_auto
        AntiFlickerMode.HZ_50 -> R.string.anti_flicker_50hz
        AntiFlickerMode.HZ_60 -> R.string.anti_flicker_60hz
    }
}
