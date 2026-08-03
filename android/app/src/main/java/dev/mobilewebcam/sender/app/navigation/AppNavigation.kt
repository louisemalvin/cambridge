package dev.mobilewebcam.sender.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.mobilewebcam.sender.feature.pairing.PairingRoute
import dev.mobilewebcam.sender.feature.settings.SettingsRoute
import dev.mobilewebcam.sender.feature.webcam.WebcamRoute

@Composable
fun AppNavigation(
    backStack: AppBackStack,
    onNavigateToWebcam: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToPairing: () -> Unit,
    onNavigateBack: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        backStack = backStack.elements,
        modifier = modifier,
        onBack = {
            if (!backStack.pop()) {
                onNavigateBack()
            }
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { destination ->
            when (destination) {
                AppDestination.Pairing -> NavEntry(destination) {
                    PairingRoute(onNavigateToWebcam = onNavigateToWebcam)
                }
                AppDestination.Webcam -> NavEntry(destination) {
                    WebcamRoute(
                        onNavigateToSettings = onNavigateToSettings,
                        onCopyDiagnostics = onCopyDiagnostics,
                    )
                }
                AppDestination.Settings -> NavEntry(destination) {
                    SettingsRoute(
                        onNavigateBack = onNavigateBack,
                        onNavigateToPairing = onNavigateToPairing,
                        onCopyDiagnostics = onCopyDiagnostics,
                    )
                }
            }
        },
    )
}
