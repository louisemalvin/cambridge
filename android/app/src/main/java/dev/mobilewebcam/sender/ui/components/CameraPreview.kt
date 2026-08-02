package dev.mobilewebcam.sender.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import dev.mobilewebcam.sender.camera.CameraInteractionState
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.camera.DisplayOrientation
import dev.mobilewebcam.sender.camera.MINIMUM_PREVIEW_DIMENSION
import dev.mobilewebcam.sender.config.CameraZoom

@Composable
fun CameraPreview(
    orientation: DisplayOrientation,
    zoomState: CameraInteractionState,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
    onZoomRatioChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnSurfaceChanged by rememberUpdatedState(onSurfaceChanged)
    val currentOnZoomRatioChanged by rememberUpdatedState(onZoomRatioChanged)
    val currentZoomRatio by rememberUpdatedState(zoomState.zoomRatio)
    val currentMinZoomRatio by rememberUpdatedState(zoomState.minZoomRatio)
    val currentMaxZoomRatio by rememberUpdatedState(zoomState.maxZoomRatio)
    val currentOrientation by rememberUpdatedState(orientation)

    AndroidView(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoomChange, _ ->
                if (zoomChange != CameraZoom.DEFAULT_ZOOM_RATIO) {
                    val requestedZoom = (currentZoomRatio * zoomChange).coerceIn(
                        currentMinZoomRatio,
                        currentMaxZoomRatio,
                    )
                    currentOnZoomRatioChanged(requestedZoom)
                }
            }
        },
        factory = { context ->
            SurfaceView(context).also { view ->
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        publishSurface(
                            view,
                            holder,
                            view.width,
                            view.height,
                            currentOrientation,
                            currentOnSurfaceChanged,
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        publishSurface(
                            view,
                            holder,
                            width,
                            height,
                            currentOrientation,
                            currentOnSurfaceChanged,
                        )
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        currentOnSurfaceChanged(null)
                    }
                })
            }
        },
        update = { view ->
            publishSurface(
                view,
                view.holder,
                view.width,
                view.height,
                currentOrientation,
                currentOnSurfaceChanged,
            )
        },
    )
}

private fun publishSurface(
    view: SurfaceView,
    holder: SurfaceHolder,
    width: Int,
    height: Int,
    fallbackOrientation: DisplayOrientation,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
) {
    val hasValidDimensions = width > MINIMUM_PREVIEW_DIMENSION &&
        height > MINIMUM_PREVIEW_DIMENSION
    if (!holder.surface.isValid || !hasValidDimensions) {
        return
    }
    val orientation = view.display?.rotation?.let { rotation ->
        DisplayOrientation.fromSurfaceRotation(rotation)
    }
        ?: fallbackOrientation
    onSurfaceChanged(
        CameraPreviewSurface(
            surface = holder.surface,
            width = width,
            height = height,
            orientation = orientation,
        ),
    )
}
