package dev.cambridge.sender.feature.setup

import android.Manifest
import android.app.Activity
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
import dev.cambridge.sender.app.lockStreamingOrientation
import dev.cambridge.sender.app.unlockStreamingOrientation
import dev.cambridge.sender.app.model.SenderScreenAction
import dev.cambridge.sender.model.StreamOrientation

@Composable
fun StreamSetupRoute(
    onNavigateToWebcam: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val viewModel: StreamSetupViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    var cameraPermissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var pendingStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (granted && pendingStart) {
            pendingStart = false
            viewModel.onAction(SenderScreenAction.StartStream)
        } else if (!granted) {
            pendingStart = false
        }
    }

    LaunchedEffect(Unit) {
        activity?.unlockStreamingOrientation()
    }
    LaunchedEffect(cameraPermissionGranted) {
        if (cameraPermissionGranted) {
            viewModel.prepareCamera()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is StreamSetupUiEffect.NavigateToWebcam -> {
                    onNavigateToWebcam()
                    activity?.lockStreamingOrientation(effect.orientation)
                }
            }
        }
    }

    StreamSetupScreen(
        state = state,
        onAction = { action ->
            when (action) {
                is SenderScreenAction.StreamOrientationSelected -> {
                    viewModel.onAction(action)
                }
                SenderScreenAction.StartStream -> {
                    if (cameraPermissionGranted) {
                        viewModel.onAction(action)
                    } else {
                        pendingStart = true
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                else -> viewModel.onAction(action)
            }
        },
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
