package dev.mobilewebcam.sender.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.ui.components.PreviewStage
import dev.mobilewebcam.sender.ui.model.PreviewOrientation
import dev.mobilewebcam.sender.ui.model.SenderDialogUiState
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.SenderScreenState
import dev.mobilewebcam.sender.ui.overlays.CameraPermissionDialog
import dev.mobilewebcam.sender.ui.overlays.ReceiverApprovalDialog

@Composable
fun SenderScreen(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
) {
    val orientation = when (LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> PreviewOrientation.PORTRAIT
        else -> PreviewOrientation.LANDSCAPE
    }

    BackHandler(enabled = state.isSettingsOpen && state.dialog == null) {
        onAction(SenderScreenAction.CloseSettings)
    }

    if (state.isSettingsOpen) {
        SettingsScreen(
            state = state,
            onAction = onAction,
        )
    } else {
        PreviewScreen(
            state = state,
            orientation = orientation,
            onAction = onAction,
            onSurfaceChanged = onSurfaceChanged,
        )
    }

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
        is SenderDialogUiState.PendingApproval -> ReceiverApprovalDialog(
            dialog = dialog,
            onAction = onAction,
        )
    }
}
