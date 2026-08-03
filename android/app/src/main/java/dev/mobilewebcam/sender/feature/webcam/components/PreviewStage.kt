package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.app.model.PreviewOrientation
import dev.mobilewebcam.sender.app.model.PreviewViewportCalculator
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.feature.webcam.WebcamUiState
import dev.mobilewebcam.sender.feature.webcam.overlays.ZoomTray
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface

@Composable
fun PreviewStage(
    state: WebcamUiState,
    orientation: PreviewOrientation,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayAspectRatio = if (orientation.isPortrait) {
        state.preview.landscapeAspectRatio.reciprocal()
    } else {
        state.preview.landscapeAspectRatio
    }

    BoxWithConstraints(
        modifier = modifier,
    ) {
        val isLandscape = maxWidth > maxHeight
        val viewport = PreviewViewportCalculator.fit(
            containerWidth = maxWidth.value,
            containerHeight = maxHeight.value,
            aspectRatio = displayAspectRatio,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(viewport.width.dp, viewport.height.dp)
                    .align(Alignment.Center),
            ) {
                CameraPreview(
                    orientation = orientation,
                    zoomState = state.camera.zoom,
                    onSurfaceChanged = onSurfaceChanged,
                    onZoomRatioChanged = { ratio ->
                        onAction(SenderScreenAction.ZoomChanged(ratio))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (state.isScreenDimmed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = SCREEN_DIM_ALPHA)),
                )
            }

            PreviewStatusOverlay(
                state = state,
                onAction = onAction,
                modifier = Modifier.fillMaxSize(),
            )

            if (state.isZoomTrayOpen) {
                ZoomTray(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .then(
                            if (isLandscape) {
                                Modifier.systemBarsPadding()
                            } else {
                                Modifier.navigationBarsPadding()
                            },
                        )
                        .padding(bottom = ZOOM_TRAY_BOTTOM_PADDING.dp),
                )
            }

            PreviewActions(
                state = state,
                isLandscape = isLandscape,
                onAction = onAction,
                modifier = Modifier
                    .align(
                        if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter,
                    )
                    .then(
                        if (isLandscape) {
                            Modifier.systemBarsPadding()
                        } else {
                            Modifier.navigationBarsPadding()
                        },
                    )
                    .padding(
                        end = if (isLandscape) {
                            ACTION_TOOLBAR_END_PADDING.dp
                        } else {
                            NO_PADDING_DP.dp
                        },
                        bottom = if (isLandscape) {
                            NO_PADDING_DP.dp
                        } else {
                            ACTION_TOOLBAR_BOTTOM_PADDING.dp
                        },
                    ),
            )
        }
    }
}

private fun Float.reciprocal(): Float = UNIT_RATIO / this

private const val UNIT_RATIO = 1.0f
private const val SCREEN_DIM_ALPHA = 0.72f
private const val ACTION_TOOLBAR_END_PADDING = 16
private const val ACTION_TOOLBAR_BOTTOM_PADDING = 16
private const val NO_PADDING_DP = 0
private const val ZOOM_TRAY_BOTTOM_PADDING = 96
