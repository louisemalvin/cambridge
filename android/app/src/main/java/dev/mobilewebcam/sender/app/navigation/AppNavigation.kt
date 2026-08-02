package dev.mobilewebcam.sender.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderScreenState
import dev.mobilewebcam.sender.feature.settings.SettingsScreen
import dev.mobilewebcam.sender.feature.webcam.WebcamScreen
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface

@Composable
fun AppNavigation(
    backStack: AppBackStack,
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
    onNavigateToPairing: () -> Unit,
    onNavigateToWebcam: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        backStack = backStack.elements,
        modifier = modifier,
        entryProvider = { destination ->
            when (destination) {
                AppDestination.Pairing -> NavEntry(destination) {
                    WebcamScreen(
                        state = state,
                        onAction = onAction,
                        onSurfaceChanged = onSurfaceChanged,
                    )
                }
                AppDestination.Webcam -> NavEntry(destination) {
                    WebcamScreen(
                        state = state,
                        onAction = onAction,
                        onSurfaceChanged = onSurfaceChanged,
                    )
                }
                AppDestination.Settings -> NavEntry(destination) {
                    SettingsScreen(
                        state = state,
                        onAction = onAction,
                    )
                }
            }
        },
    )
}
