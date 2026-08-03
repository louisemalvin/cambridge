package dev.mobilewebcam.sender.feature.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.mobilewebcam.sender.app.model.SenderScreenAction

@Composable
fun PairingRoute(
    onNavigateToWebcam: () -> Unit,
) {
    val viewModel: PairingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    val dialog = (state as? PairingUiState.AwaitingApproval)?.let { awaiting ->
        ReceiverApprovalUiState(awaiting.receiverName)
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PairingUiEffect.NavigateToWebcam -> onNavigateToWebcam()
            }
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
