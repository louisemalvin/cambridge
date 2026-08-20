package dev.cambridge.sender.feature.webcam

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import dev.cambridge.sender.app.model.PreviewOrientation
import dev.cambridge.sender.app.model.SenderScreenAction
import dev.cambridge.sender.feature.webcam.components.PreviewStage
import dev.cambridge.sender.media.camera.CameraPreviewSurface

@Composable
fun WebcamScreen(
    state: WebcamUiState,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
) {
    val orientation = when (LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> PreviewOrientation.PORTRAIT
        else -> PreviewOrientation.LANDSCAPE
    }

    PreviewScreen(
        state = state,
        orientation = orientation,
        onAction = onAction,
        onSurfaceChanged = onSurfaceChanged,
    )
}

@Composable
private fun PreviewScreen(
    state: WebcamUiState,
    orientation: PreviewOrientation,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
) {
    PreviewStage(
        state = state,
        orientation = orientation,
        onAction = onAction,
        onSurfaceChanged = onSurfaceChanged,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}
