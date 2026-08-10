package dev.cambridge.sender.media.camera

import kotlinx.coroutines.flow.StateFlow
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.VideoProfile

/** Camera controls that can be consumed by platform-neutral UI state. */
interface CameraInteractionController {
    val state: StateFlow<CameraInteractionState>

    suspend fun prepareCamera()

    suspend fun setZoomRatio(zoomRatio: Float)

    suspend fun resetZoom()

    suspend fun setStabilizationMode(mode: CameraStabilizationMode) {
        Unit
    }

    suspend fun setAntiFlickerMode(mode: AntiFlickerMode)

    suspend fun selectPhysicalLens(lens: PhysicalLensOption)
}

/** Android-only preview surface lifecycle boundary. */
interface CameraPreviewSurfaceController {
    suspend fun setPreviewSurface(surface: CameraPreviewSurface?)
}

interface CameraController : CameraInteractionController, CameraPreviewSurfaceController
{
    suspend fun supportedVideoModes(modes: List<VideoProfile>): Set<String> = modes.map { it.id }.toSet()

    suspend fun snapshotSessionTransform(
        codedWidth: Int,
        codedHeight: Int,
        orientation: StreamOrientation,
    ): SessionTransform
}
