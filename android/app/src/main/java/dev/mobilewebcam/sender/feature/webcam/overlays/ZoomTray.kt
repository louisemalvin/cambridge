package dev.mobilewebcam.sender.feature.webcam.overlays

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.feature.webcam.components.CameraZoomControls
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.SenderScreenState

@Composable
fun ZoomTray(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ZOOM_TRAY_HORIZONTAL_PADDING.dp),
        shape = RoundedCornerShape(ZOOM_TRAY_CORNER_RADIUS.dp),
        tonalElevation = ZOOM_TRAY_TONAL_ELEVATION.dp,
    ) {
        CameraZoomControls(
            state = state.camera.zoom,
            onZoomRatioChanged = { ratio -> onAction(SenderScreenAction.ZoomChanged(ratio)) },
            onResetZoom = { onAction(SenderScreenAction.ResetZoom) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val ZOOM_TRAY_HORIZONTAL_PADDING = 16
private const val ZOOM_TRAY_CORNER_RADIUS = 20
private const val ZOOM_TRAY_TONAL_ELEVATION = 3
