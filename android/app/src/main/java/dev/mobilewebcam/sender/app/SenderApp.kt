package dev.mobilewebcam.sender.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dev.mobilewebcam.sender.MobileWebcamApplication
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderUiEffect
import dev.mobilewebcam.sender.app.navigation.AppDestination
import dev.mobilewebcam.sender.app.navigation.AppNavigation
import dev.mobilewebcam.sender.app.navigation.rememberAppBackStack
import dev.mobilewebcam.sender.app.startup.StartupStateResolver
import dev.mobilewebcam.sender.feature.webcam.WebcamViewModel

@Composable
fun SenderApp() {
    val context = LocalContext.current
    val application = context.applicationContext as MobileWebcamApplication
    val viewModel: WebcamViewModel = hiltViewModel()
    val screenState by viewModel.uiState.collectAsState()
    val diagnosticsClipboardLabel = stringResource(R.string.diagnostics_clipboard_label)
    val errorDetailsCopiedMessage = stringResource(R.string.error_details_copied)
    var cameraPermissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }

    val initialDestination = remember {
        StartupStateResolver(application.pairings).resolveInitialDestination()
    }
    val backStack = rememberAppBackStack(initialDestination)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        viewModel.setCameraPermissionGranted(granted)
    }

    LaunchedEffect(cameraPermissionGranted) {
        viewModel.setCameraPermissionGranted(cameraPermissionGranted)
    }

    LaunchedEffect(viewModel, permissionLauncher) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SenderUiEffect.RequestCameraPermission -> {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                is SenderUiEffect.CopyDiagnostics -> {
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE,
                    ) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText(
                            diagnosticsClipboardLabel,
                            effect.details,
                        ),
                    )
                    Toast.makeText(
                        context,
                        errorDetailsCopiedMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    dev.mobilewebcam.sender.app.theme.MobileWebcamTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppNavigation(
                backStack = backStack,
                state = dev.mobilewebcam.sender.app.model.SenderScreenState(
                    preview = screenState.preview,
                    connection = screenState.connection,
                    camera = screenState.camera,
                    isScreenDimmed = screenState.isScreenDimmed,
                    isZoomTrayOpen = screenState.isZoomTrayOpen,
                    cameraPermissionGranted = screenState.cameraPermissionGranted,
                    failureDiagnostics = screenState.failureDiagnostics,
                ),
                onAction = { action ->
                    when (action) {
                        SenderScreenAction.OpenSettings -> backStack.navigateTo(AppDestination.Settings)
                        SenderScreenAction.CloseSettings -> backStack.pop()
                        else -> viewModel.onAction(action)
                    }
                },
                onSurfaceChanged = viewModel::setPreviewSurface,
                onNavigateToPairing = { backStack.popToPairing() },
                onNavigateToWebcam = { backStack.replaceWithWebcam() },
                onNavigateToSettings = { backStack.navigateTo(AppDestination.Settings) },
                onNavigateBack = { backStack.pop() },
            )
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
