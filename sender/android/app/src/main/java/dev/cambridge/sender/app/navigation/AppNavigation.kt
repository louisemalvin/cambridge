package dev.cambridge.sender.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.cambridge.sender.app.permission.CameraPermissionSnapshot
import dev.cambridge.sender.feature.settings.SettingsRoute
import dev.cambridge.sender.feature.permission.CameraPermissionRoute
import dev.cambridge.sender.feature.setup.StreamSetupRoute
import dev.cambridge.sender.feature.webcam.WebcamRoute

@Composable
fun AppNavigation(
    backStack: AppBackStack,
    onNavigateToWebcam: () -> Unit,
    onNavigateToStreamSetup: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit,
    onRequestStopStream: () -> Unit,
    onNavigateBackFromWebcam: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    cameraPermission: CameraPermissionSnapshot,
    onPermissionRequestStarted: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onOpenCameraPermissionSettings: () -> Unit,
    onCameraPermissionLost: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        backStack = backStack.elements,
        modifier = modifier,
        onBack = {
            if (backStack.current == AppDestination.Webcam) {
                onNavigateBackFromWebcam()
            } else if (!backStack.pop()) {
                onNavigateBack()
            }
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { destination ->
            when (destination) {
                AppDestination.CameraPermission -> NavEntry(destination) {
                    CameraPermissionRoute(
                        permission = cameraPermission,
                        onPermissionRequestStarted = onPermissionRequestStarted,
                        onPermissionResult = onPermissionResult,
                        onOpenSettings = onOpenCameraPermissionSettings,
                    )
                }
                AppDestination.StreamSetup -> NavEntry(destination) {
                    StreamSetupRoute(
                        onNavigateToWebcam = onNavigateToWebcam,
                        onCameraPermissionLost = onCameraPermissionLost,
                    )
                }
                AppDestination.Webcam -> NavEntry(destination) {
                    WebcamRoute(
                        onNavigateToSettings = onNavigateToSettings,
                        onNavigateToStreamSetup = onNavigateToStreamSetup,
                        onRequestStopStream = onRequestStopStream,
                        onNavigateBack = onNavigateBackFromWebcam,
                        onCopyDiagnostics = onCopyDiagnostics,
                    )
                }
                AppDestination.Settings -> NavEntry(destination) {
                    SettingsRoute(
                        onNavigateBack = onNavigateBack,
                        onRequestStopStream = onRequestStopStream,
                        onCopyDiagnostics = onCopyDiagnostics,
                    )
                }
            }
        },
    )
}
