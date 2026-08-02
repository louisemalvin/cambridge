package dev.mobilewebcam.sender.feature.webcam.overlays

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.value

@Composable
fun CameraPermissionDialog(
    dialog: SenderDialogUiState.CameraPermission,
    onAction: (SenderScreenAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(SenderScreenAction.DismissPermissionDialog) },
        title = { Text(dialog.title.value()) },
        text = { Text(dialog.message.value()) },
        confirmButton = {
            Button(onClick = { onAction(SenderScreenAction.RequestCameraPermission) }) {
                Text(stringResource(R.string.allow_camera_access))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(SenderScreenAction.DismissPermissionDialog) }) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
