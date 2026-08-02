package dev.mobilewebcam.sender.feature.pairing.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.SenderDialogUiState
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.value

@Composable
fun ReceiverApprovalDialog(
    dialog: SenderDialogUiState.PendingApproval,
    onAction: (SenderScreenAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(SenderScreenAction.RejectPending) },
        title = { Text(stringResource(R.string.approve_this_computer)) },
        text = {
            Text(
                stringResource(
                    R.string.pending_approval_message,
                    dialog.receiverName.value(),
                ),
            )
        },
        confirmButton = {
            Button(onClick = { onAction(SenderScreenAction.ApprovePending) }) {
                Text(stringResource(R.string.approve_this_computer))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(SenderScreenAction.RejectPending) }) {
                Text(stringResource(R.string.reject))
            }
        },
    )
}
