package dev.cambridge.sender.feature.webcam

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import dev.cambridge.sender.app.model.SenderScreenAction
import dev.cambridge.sender.app.model.SenderUiEffect

@Composable
fun WebcamRoute(
    onNavigateToSettings: () -> Unit,
    onNavigateToStreamSetup: () -> Unit,
    onRequestStopStream: () -> Unit,
    onNavigateBack: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
) {
    val viewModel: WebcamViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    BackHandler(onBack = onNavigateBack)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is SenderUiEffect.CopyDiagnostics -> onCopyDiagnostics(effect.details)
            }
        }
    }

    WebcamScreen(
        state = state,
        onAction = { action ->
            when (action) {
                SenderScreenAction.OpenSettings -> onNavigateToSettings()
                SenderScreenAction.RequestStopStream -> {
                    onRequestStopStream()
                }
                SenderScreenAction.StartStream -> onNavigateToStreamSetup()
                else -> viewModel.onAction(action)
            }
        },
        onSurfaceChanged = viewModel::setPreviewSurface,
    )
}
