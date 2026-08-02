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
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.config.CameraZoom
import dev.mobilewebcam.sender.ui.model.ZoomUiState

@Composable
fun CameraZoomControls(
    state: ZoomUiState,
    onZoomRatioChanged: (Float) -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoomContentDescription = androidx.compose.ui.res.stringResource(
        R.string.camera_zoom_level,
    )
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CAMERA_ZOOM_CONTENT_PADDING.dp),
            horizontalArrangement = Arrangement.spacedBy(CAMERA_ZOOM_ITEM_SPACING.dp),
        ) {
            Text(
                text = androidx.compose.ui.res.stringResource(R.string.zoom_level, state.ratio),
                modifier = Modifier.weight(ZOOM_LABEL_WEIGHT),
            )
            if (state.isSupported) {
                Slider(
                    value = state.ratio,
                    onValueChange = onZoomRatioChanged,
                    valueRange = state.minimumRatio..state.maximumRatio,
                    steps = CONTINUOUS_SLIDER_STEPS,
                    modifier = Modifier
                        .weight(ZOOM_SLIDER_WEIGHT)
                        .semantics {
                            contentDescription = zoomContentDescription
                        },
                )
            } else {
                Text(
                    text = androidx.compose.ui.res.stringResource(R.string.zoom_unavailable),
                    modifier = Modifier.weight(ZOOM_SLIDER_WEIGHT),
                )
            }
            TextButton(
                onClick = onResetZoom,
                enabled = state.isCameraActive &&
                    state.ratio != CameraZoom.DEFAULT_ZOOM_RATIO,
            ) {
                Text(androidx.compose.ui.res.stringResource(R.string.reset))
            }
        }
    }
}

private const val CAMERA_ZOOM_CONTENT_PADDING = 12
private const val CAMERA_ZOOM_ITEM_SPACING = 8
private const val CONTINUOUS_SLIDER_STEPS = 0
private const val ZOOM_LABEL_WEIGHT = 0.25f
private const val ZOOM_SLIDER_WEIGHT = 1.0f
