package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.ZoomUiState
import java.util.Locale

@Composable
fun CameraZoomControls(
    state: ZoomUiState,
    onZoomRatioChanged: (Float) -> Unit,
    onResetZoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isSupported) return

    Row(
        modifier = modifier.padding(ZOOM_CONTROLS_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZOOM_SPACING.dp),
    ) {
        Text(
            text = String.format(Locale.US, ZOOM_RATIO_FORMAT, state.ratio),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = state.ratio,
            onValueChange = onZoomRatioChanged,
            valueRange = state.minimumRatio..state.maximumRatio,
            enabled = state.isCameraActive,
            modifier = Modifier.weight(SLIDER_WEIGHT),
        )
        TextButton(
            onClick = onResetZoom,
            enabled = state.isCameraActive,
        ) {
            Text(stringResource(R.string.reset))
        }
    }
}

private const val ZOOM_RATIO_FORMAT = "%.1fx"
private const val ZOOM_CONTROLS_PADDING = 8
private const val ZOOM_SPACING = 8
private const val SLIDER_WEIGHT = 1.0f
