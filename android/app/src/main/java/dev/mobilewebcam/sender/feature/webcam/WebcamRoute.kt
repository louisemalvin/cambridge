package dev.mobilewebcam.sender.feature.webcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderUiEffect

@Composable
fun WebcamRoute(
    onNavigateToSettings: () -> Unit,
    onNavigateToStreamSetup: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: WebcamViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    var cameraPermissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        viewModel.setCameraPermissionGranted(granted)
    }

    LaunchedEffect(cameraPermissionGranted) {
        viewModel.setCameraPermissionGranted(cameraPermissionGranted)
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SenderUiEffect.RequestCameraPermission -> {
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                }
                is SenderUiEffect.CopyDiagnostics -> onCopyDiagnostics(effect.details)
                SenderUiEffect.NavigateToPairing -> Unit
            }
        }
    }

    WebcamScreen(
        state = state,
        onAction = { action ->
            when (action) {
                SenderScreenAction.OpenSettings -> onNavigateToSettings()
                SenderScreenAction.StopStream -> {
                    viewModel.onAction(action)
                    onNavigateToStreamSetup()
                }
                SenderScreenAction.StartStream -> onNavigateToStreamSetup()
                else -> viewModel.onAction(action)
            }
        },
        onSurfaceChanged = viewModel::setPreviewSurface,
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
