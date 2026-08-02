package dev.mobilewebcam.sender.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.camera.CameraInteractionState
import dev.mobilewebcam.sender.config.CameraZoom
import java.util.Locale

@Composable
fun CameraZoomControls(
    state: CameraInteractionState,
    onZoomRatioChanged: (Float) -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CAMERA_ZOOM_CONTENT_PADDING.dp),
            horizontalArrangement = Arrangement.spacedBy(CAMERA_ZOOM_ITEM_SPACING.dp),
        ) {
            Text(
                text = String.format(Locale.US, ZOOM_LABEL_FORMAT, state.zoomRatio),
                modifier = Modifier.weight(ZOOM_LABEL_WEIGHT),
            )
            if (state.isZoomSupported) {
                Slider(
                    value = state.zoomRatio,
                    onValueChange = onZoomRatioChanged,
                    valueRange = state.minZoomRatio..state.maxZoomRatio,
                    steps = CONTINUOUS_SLIDER_STEPS,
                    modifier = Modifier
                        .weight(ZOOM_SLIDER_WEIGHT)
                        .semantics { contentDescription = ZOOM_CONTROL_DESCRIPTION },
                )
            } else {
                Text(
                    text = ZOOM_UNAVAILABLE_LABEL,
                    modifier = Modifier.weight(ZOOM_SLIDER_WEIGHT),
                )
            }
            TextButton(
                onClick = onResetZoom,
                enabled = state.isCameraActive &&
                    state.zoomRatio != CameraZoom.DEFAULT_ZOOM_RATIO,
            ) {
                Text(RESET_ZOOM_LABEL)
            }
        }
    }
}

private const val CAMERA_ZOOM_CONTENT_PADDING = 12
private const val CAMERA_ZOOM_ITEM_SPACING = 8
private const val CONTINUOUS_SLIDER_STEPS = 0
private const val ZOOM_LABEL_WEIGHT = 0.25f
private const val ZOOM_SLIDER_WEIGHT = 1.0f
private const val ZOOM_LABEL_FORMAT = "%.1fx"
private const val ZOOM_CONTROL_DESCRIPTION = "Camera zoom level"
private const val ZOOM_UNAVAILABLE_LABEL = "1x only"
private const val RESET_ZOOM_LABEL = "Reset"
