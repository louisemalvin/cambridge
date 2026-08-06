package dev.mobilewebcam.sender.feature.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.mobilewebcam.sender.R
import androidx.compose.ui.res.stringResource

@Composable
fun PairingRoute(
    onNavigateToStreamSetup: () -> Unit,
) {
    val viewModel: PairingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PairingUiEffect.NavigateToStreamSetup -> onNavigateToStreamSetup()
            }
        }
    }

    PairingScreen(
        state = state,
        computerName = stringResource(R.string.computer_name),
        onAction = { action ->
            if (action == dev.mobilewebcam.sender.app.model.SenderScreenAction.ConnectReceiver) {
                onNavigateToStreamSetup()
            } else {
                viewModel.onAction(action)
            }
        },
    )
}
