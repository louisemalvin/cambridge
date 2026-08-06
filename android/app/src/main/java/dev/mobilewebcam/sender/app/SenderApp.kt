package dev.mobilewebcam.sender.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.navigation.AppDestination
import dev.mobilewebcam.sender.app.navigation.AppNavigation
import dev.mobilewebcam.sender.app.navigation.rememberAppBackStack
import dev.mobilewebcam.sender.app.startup.StartupStateResolver
import dev.mobilewebcam.sender.model.SenderSettingsRepository

@Composable
fun SenderApp(
    settings: SenderSettingsRepository,
) {
    val context = LocalContext.current
    val senderSettings by settings.state.collectAsState()
    val initialDestination = remember(senderSettings.receiverEndpoint) {
        StartupStateResolver(
            hasConfiguredReceiver = senderSettings.receiverEndpoint != null,
        ).resolveInitialDestination()
    }
    val backStack = rememberAppBackStack(initialDestination)
    val diagnosticsClipboardLabel = stringResource(R.string.diagnostics_clipboard_label)
    val errorDetailsCopiedMessage = stringResource(R.string.error_details_copied)

    dev.mobilewebcam.sender.app.theme.MobileWebcamTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppNavigation(
                backStack = backStack,
                onNavigateToWebcam = { backStack.replaceWithWebcam() },
                onNavigateToStreamSetup = { backStack.replaceWithStreamSetup() },
                onNavigateToSettings = { backStack.navigateTo(AppDestination.Settings) },
                onNavigateToPairing = { backStack.popToPairing() },
                onNavigateBack = { backStack.pop() },
                onCopyDiagnostics = { details ->
                    context.copyDiagnostics(
                        label = diagnosticsClipboardLabel,
                        details = details,
                        copiedMessage = errorDetailsCopiedMessage,
                    )
                },
            )
        }
    }
}

private fun Context.copyDiagnostics(
    label: String,
    details: String,
    copiedMessage: String,
) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, details))
    Toast.makeText(this, copiedMessage, Toast.LENGTH_SHORT).show()
}
