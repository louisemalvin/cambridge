package dev.mobilewebcam.sender.feature.settings

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.mobilewebcam.sender.app.lockStreamingOrientation
import dev.mobilewebcam.sender.app.unlockStreamingOrientation
import androidx.hilt.navigation.compose.hiltViewModel
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderUiEffect

@Composable
fun SettingsRoute(
    onNavigateBack: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val activity = context as? Activity
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
            } else if (action == SenderScreenAction.StartStream) {
                activity?.lockStreamingOrientation(configuration.orientation)
                viewModel.onAction(action)
            } else if (action == SenderScreenAction.StopStream) {
                viewModel.onAction(action)
                activity?.unlockStreamingOrientation()
            } else {
                viewModel.onAction(action)
            }
        },
    )
}
