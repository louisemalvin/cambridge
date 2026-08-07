package dev.mobilewebcam.sender.feature.webcam.overlays

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.mobilewebcam.sender.R

@Composable
fun EndStreamConfirmationDialog(
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val confirmDescription = stringResource(R.string.confirm_end_stream)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(R.string.end_stream_title)) },
        text = { Text(stringResource(R.string.end_stream_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics {
                    contentDescription = confirmDescription
                },
            ) {
                Text(stringResource(R.string.end_stream_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.keep_streaming))
            }
        },
    )
}
