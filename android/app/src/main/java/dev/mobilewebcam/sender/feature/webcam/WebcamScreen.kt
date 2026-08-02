package dev.mobilewebcam.sender.feature.webcam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import dev.mobilewebcam.sender.app.model.PreviewOrientation
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderScreenState
import dev.mobilewebcam.sender.app.model.SenderUiEffect
import dev.mobilewebcam.sender.feature.webcam.components.PreviewStage
import dev.mobilewebcam.sender.feature.webcam.overlays.CameraPermissionDialog
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface

@Composable
fun WebcamRoute(
    onNavigateToSettings: () -> Unit,
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
            }
        }
    }

    WebcamScreen(
        state = state,
        onAction = { action ->
            when (action) {
                SenderScreenAction.OpenSettings -> onNavigateToSettings()
                else -> viewModel.onAction(action)
            }
        },
        onSurfaceChanged = viewModel::setPreviewSurface,
    )
}

@Composable
fun WebcamScreen(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
) {
    val orientation = when (LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> PreviewOrientation.PORTRAIT
        else -> PreviewOrientation.LANDSCAPE
    }

    PreviewScreen(
        state = state,
        orientation = orientation,
        onAction = onAction,
        onSurfaceChanged = onSurfaceChanged,
    )

    state.dialog?.let { dialog ->
        SenderDialog(
            dialog = dialog,
            onAction = onAction,
        )
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun PreviewScreen(
    state: SenderScreenState,
    orientation: PreviewOrientation,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
) {
    PreviewStage(
        state = state,
        orientation = orientation,
        onAction = onAction,
        onSurfaceChanged = onSurfaceChanged,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    )
}

@Composable
private fun SenderDialog(
    dialog: SenderDialogUiState,
    onAction: (SenderScreenAction) -> Unit,
) {
    when (dialog) {
        is SenderDialogUiState.CameraPermission -> CameraPermissionDialog(
            dialog = dialog,
            onAction = onAction,
        )
        is SenderDialogUiState.PendingApproval -> Unit
    }
}
