package dev.mobilewebcam.sender.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.value
import dev.mobilewebcam.sender.feature.pairing.components.ReceiverApprovalDialog

@Composable
fun PairingRoute(
    onNavigateToWebcam: () -> Unit,
) {
    val viewModel: PairingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    val dialog = (state as? PairingUiState.AwaitingApproval)?.let { awaiting ->
        ReceiverApprovalUiState(awaiting.receiverName)
    }

    LaunchedEffect(state) {
        if (state is PairingUiState.Connected) {
            onNavigateToWebcam()
        }
    }

    PairingScreen(
        state = state,
        dialog = dialog,
        onAction = { action ->
            when (action) {
                SenderScreenAction.ApprovePending -> viewModel.approvePending()
                SenderScreenAction.RejectPending -> viewModel.rejectPending()
                else -> Unit
            }
        },
    )
}

@Composable
fun PairingScreen(
    state: PairingUiState,
    dialog: ReceiverApprovalUiState?,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(CARD_PADDING.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(CARD_CONTENT_PADDING.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CARD_ITEM_SPACING.dp),
                ) {
                    when (state) {
                        PairingUiState.Idle,
                        is PairingUiState.Searching -> {
                            Text(
                                text = stringResource(R.string.waiting_for_connection),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.waiting_for_connection_support),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is PairingUiState.AwaitingApproval -> {
                            Text(
                                text = stringResource(R.string.approve_this_computer),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = state.receiverName.value(),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is PairingUiState.Connecting -> {
                            Text(
                                text = state.message.value(),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        is PairingUiState.Connected -> {
                            Text(
                                text = stringResource(R.string.connected_to_receiver, state.receiverName.value()),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        is PairingUiState.Failed -> {
                            Text(
                                text = state.message.value(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }

        dialog?.let { pending ->
            ReceiverApprovalDialog(
                dialog = pending,
                onAction = onAction,
            )
        }
    }
}

private const val CARD_PADDING = 16
private const val CARD_CONTENT_PADDING = 20
private const val CARD_ITEM_SPACING = 8
