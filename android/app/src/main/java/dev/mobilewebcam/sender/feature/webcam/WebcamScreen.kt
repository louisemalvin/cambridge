package dev.mobilewebcam.sender.feature.webcam

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import dev.mobilewebcam.sender.app.model.PreviewOrientation
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderScreenState
import dev.mobilewebcam.sender.feature.webcam.components.PreviewStage
import dev.mobilewebcam.sender.feature.webcam.overlays.CameraPermissionDialog
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface

@Composable
fun WebcamScreen(
    state: SenderScreenState,
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

    state.dialog?.let { dialog ->
        SenderDialog(
            dialog = dialog,
            onAction = onAction,
        )
    }
}

@Composable
private fun PreviewScreen(
    state: SenderScreenState,
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

@Composable
private fun SenderDialog(
    dialog: SenderDialogUiState,
    onAction: (SenderScreenAction) -> Unit,
) {
    when (dialog) {
        is SenderDialogUiState.CameraPermission -> CameraPermissionDialog(
            dialog = dialog,
            onAction = onAction,
        )
        is SenderDialogUiState.PendingApproval -> Unit
    }
}
