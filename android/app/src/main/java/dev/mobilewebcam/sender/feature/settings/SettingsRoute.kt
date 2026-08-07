package dev.mobilewebcam.sender.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderUiEffect

@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit,
    onRequestStopStream: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
) {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SenderUiEffect.CopyDiagnostics -> onCopyDiagnostics(effect.details)
                SenderUiEffect.RequestCameraPermission -> Unit
                SenderUiEffect.NavigateToPairing -> onNavigateToPairing()
            }
        }
    }

    SettingsScreen(
        state = state,
        onAction = { action ->
            if (action == SenderScreenAction.CloseSettings) {
                onNavigateBack()
            } else if (action == SenderScreenAction.RequestStopStream) {
                onRequestStopStream()
            } else {
                viewModel.onAction(action)
            }
        },
    )
}
