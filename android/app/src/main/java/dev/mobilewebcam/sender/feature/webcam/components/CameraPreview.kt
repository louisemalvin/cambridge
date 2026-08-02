package dev.mobilewebcam.sender.feature.webcam.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import dev.mobilewebcam.sender.app.model.PreviewOrientation
import dev.mobilewebcam.sender.app.model.ZoomUiState
import dev.mobilewebcam.sender.config.CameraZoom
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.media.camera.DisplayOrientation
import dev.mobilewebcam.sender.media.camera.MINIMUM_PREVIEW_DIMENSION

@Composable
fun CameraPreview(
    orientation: PreviewOrientation,
    zoomState: ZoomUiState,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
    onZoomRatioChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnSurfaceChanged by rememberUpdatedState(onSurfaceChanged)
    val currentOnZoomRatioChanged by rememberUpdatedState(onZoomRatioChanged)
    val currentZoomRatio by rememberUpdatedState(zoomState.ratio)
    val currentMinZoomRatio by rememberUpdatedState(zoomState.minimumRatio)
    val currentMaxZoomRatio by rememberUpdatedState(zoomState.maximumRatio)
    val currentOrientation = orientation.toDisplayOrientation()

    AndroidView(
        modifier = modifier.pointerInput(zoomState.isCameraActive, zoomState.isSupported) {
            if (!zoomState.isCameraActive || !zoomState.isSupported) return@pointerInput
            detectTransformGestures { _, _, zoomFactor, _ ->
                val targetZoom = (currentZoomRatio * zoomFactor)
                    .coerceIn(currentMinZoomRatio, currentMaxZoomRatio)
                if (targetZoom != currentZoomRatio) {
                    currentOnZoomRatioChanged(targetZoom)
                }
            }
        },
        factory = { viewContext ->
            SurfaceView(viewContext).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            publishSurface(
                                this@apply,
                                holder,
                                width,
                                height,
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
                                this@apply,
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
                    },
                )
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

private fun PreviewOrientation.toDisplayOrientation(): DisplayOrientation = when (this) {
    PreviewOrientation.PORTRAIT -> DisplayOrientation.PORTRAIT
    PreviewOrientation.LANDSCAPE -> DisplayOrientation.LANDSCAPE
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

    val display = view.display
    val orientation = if (display != null) {
        DisplayOrientation.fromSurfaceRotation(display.rotation)
    } else {
        fallbackOrientation
    }

    onSurfaceChanged(
        CameraPreviewSurface(
            surface = holder.surface,
            width = width,
            height = height,
            orientation = orientation,
        ),
    )
}
