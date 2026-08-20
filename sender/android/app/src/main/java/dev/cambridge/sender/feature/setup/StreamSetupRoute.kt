package dev.cambridge.sender.feature.setup

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.cambridge.sender.app.lockStreamingOrientation
import dev.cambridge.sender.app.unlockStreamingOrientation

@Composable
fun StreamSetupRoute(
    onNavigateToWebcam: () -> Unit,
    onCameraPermissionLost: () -> Unit,
) {
    val activity = LocalActivity.current
    val viewModel: StreamSetupViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        activity?.unlockStreamingOrientation()
        viewModel.prepareCamera()
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is StreamSetupUiEffect.NavigateToWebcam -> {
                    onNavigateToWebcam()
                    activity?.lockStreamingOrientation(effect.orientation)
                }
                StreamSetupUiEffect.CameraPermissionRequired -> onCameraPermissionLost()
            }
        }
    }

    StreamSetupScreen(
        state = state,
        onAction = { action -> viewModel.onAction(action) },
    )
}
