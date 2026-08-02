package dev.mobilewebcam.sender.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.ui.SenderScreen
import dev.mobilewebcam.sender.ui.SettingsScreen
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.SenderScreenState

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
                    SenderScreen(
                        state = state,
                        onAction = onAction,
                        onSurfaceChanged = onSurfaceChanged,
                    )
                }
                AppDestination.Webcam -> NavEntry(destination) {
                    SenderScreen(
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
