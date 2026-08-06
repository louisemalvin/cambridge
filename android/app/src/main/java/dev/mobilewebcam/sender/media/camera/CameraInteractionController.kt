package dev.mobilewebcam.sender.media.camera

import kotlinx.coroutines.flow.StateFlow
import dev.mobilewebcam.sender.model.StreamOrientation

/** Camera controls that can be consumed by platform-neutral UI state. */
interface CameraInteractionController {
    val state: StateFlow<CameraInteractionState>

    suspend fun setZoomRatio(zoomRatio: Float)

    suspend fun resetZoom()

    suspend fun setStabilizationEnabled(enabled: Boolean)

    suspend fun selectPhysicalLens(lens: PhysicalLensOption)
}

/** Android-only preview surface lifecycle boundary. */
interface CameraPreviewSurfaceController {
    suspend fun setPreviewSurface(surface: CameraPreviewSurface?)
}

interface CameraController : CameraInteractionController, CameraPreviewSurfaceController
{
    suspend fun snapshotSessionTransform(
        codedWidth: Int,
        codedHeight: Int,
        orientation: StreamOrientation,
    ): SessionTransform
}
