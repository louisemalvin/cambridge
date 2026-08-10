package dev.cambridge.sender.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.cambridge.sender.R
import dev.cambridge.sender.app.navigation.AppDestination
import dev.cambridge.sender.app.navigation.AppNavigation
import dev.cambridge.sender.app.navigation.rememberAppBackStack
import dev.cambridge.sender.app.startup.StartupStateResolver
import dev.cambridge.sender.connection.SenderConnectionCoordinator
import dev.cambridge.sender.feature.webcam.overlays.EndStreamConfirmationDialog
import dev.cambridge.sender.model.isSessionActive
import dev.cambridge.sender.model.requiresStopConfirmation
import kotlinx.coroutines.launch

@Composable
fun SenderApp(
    connectionCoordinator: SenderConnectionCoordinator,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val streamState by connectionCoordinator.streamState.collectAsState()
    val initialDestination = StartupStateResolver(
        hasActiveStream = streamState.isSessionActive,
    ).resolveInitialDestination()
    val backStack = rememberAppBackStack(initialDestination)
    val coroutineScope = rememberCoroutineScope()
    var isEndStreamConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val diagnosticsClipboardLabel = stringResource(R.string.diagnostics_clipboard_label)
    val errorDetailsCopiedMessage = stringResource(R.string.error_details_copied)

    LaunchedEffect(streamState) {
        if (!streamState.requiresStopConfirmation) {
            isEndStreamConfirmationVisible = false
        }
    }

    fun requestStopStream() {
        if (streamState.requiresStopConfirmation) {
            isEndStreamConfirmationVisible = true
        }
    }

    fun navigateBackFromWebcam() {
        if (streamState.requiresStopConfirmation) {
            isEndStreamConfirmationVisible = true
        } else if (!streamState.isSessionActive) {
            backStack.replaceWithStreamSetup()
        }
    }

    fun confirmStopStream() {
        isEndStreamConfirmationVisible = false
        coroutineScope.launch {
            if (connectionCoordinator.stop().isSuccess) {
                backStack.replaceWithStreamSetup()
            }
        }
    }

    dev.cambridge.sender.app.theme.CamBridgeTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppNavigation(
                backStack = backStack,
                onNavigateToWebcam = { backStack.replaceWithWebcam() },
                onNavigateToStreamSetup = { backStack.replaceWithStreamSetup() },
                onNavigateToSettings = { backStack.navigateTo(AppDestination.Settings) },
                onNavigateBack = { activity?.finish() },
                onRequestStopStream = ::requestStopStream,
                onNavigateBackFromWebcam = ::navigateBackFromWebcam,
                onCopyDiagnostics = { details ->
                    context.copyDiagnostics(
                        label = diagnosticsClipboardLabel,
                        details = details,
                        copiedMessage = errorDetailsCopiedMessage,
                    )
                },
            )
        }
        if (isEndStreamConfirmationVisible) {
            EndStreamConfirmationDialog(
                onDismissRequest = { isEndStreamConfirmationVisible = false },
                onConfirm = ::confirmStopStream,
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
