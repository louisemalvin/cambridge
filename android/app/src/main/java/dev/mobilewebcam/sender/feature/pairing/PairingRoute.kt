package dev.mobilewebcam.sender.feature.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun PairingRoute(
    onNavigateToWebcam: () -> Unit,
) {
    val viewModel: PairingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    val receiverOrigin by viewModel.receiverOrigin.collectAsState()
    val receiverOriginError by viewModel.receiverOriginError.collectAsState()
    val discoveryState by viewModel.discoveryState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PairingUiEffect.NavigateToWebcam -> onNavigateToWebcam()
            }
        }
    }

    PairingScreen(
        state = state,
        receiverOrigin = receiverOrigin,
        receiverOriginError = receiverOriginError,
        discoveryState = discoveryState,
        onAction = viewModel::onAction,
    )
}
