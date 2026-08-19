package dev.cambridge.sender.feature.webcam

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import dev.cambridge.sender.app.model.SenderScreenAction
import dev.cambridge.sender.app.model.SenderUiEffect
import dev.cambridge.sender.app.permission.cameraPermissionIsPermanentlyDenied
import dev.cambridge.sender.app.permission.hasCameraPermission

@Composable
fun WebcamRoute(
    onNavigateToSettings: () -> Unit,
    onNavigateToStreamSetup: () -> Unit,
    onRequestStopStream: () -> Unit,
    onNavigateBack: () -> Unit,
    onCopyDiagnostics: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModel: WebcamViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()
    var cameraPermissionGranted by remember {
        mutableStateOf(context.hasCameraPermission())
    }
    var cameraPermissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var cameraPermissionRequestedBefore by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        cameraPermissionPermanentlyDenied = !granted && activity?.cameraPermissionIsPermanentlyDenied(
            cameraPermissionRequestedBefore,
        ) == true
        cameraPermissionRequestedBefore = true
        viewModel.setCameraPermissionState(granted, cameraPermissionPermanentlyDenied)
    }

    fun openCameraPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    LaunchedEffect(cameraPermissionGranted, cameraPermissionPermanentlyDenied) {
        viewModel.setCameraPermissionState(cameraPermissionGranted, cameraPermissionPermanentlyDenied)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = context.hasCameraPermission()
                cameraPermissionGranted = granted
                if (granted) cameraPermissionPermanentlyDenied = false
                viewModel.setCameraPermissionState(granted, cameraPermissionPermanentlyDenied)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onNavigateBack)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SenderUiEffect.RequestCameraPermission -> {
                    if (cameraPermissionPermanentlyDenied) {
                        openCameraPermissionSettings()
                    } else {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
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
                SenderScreenAction.RequestStopStream -> {
                    onRequestStopStream()
                }
                SenderScreenAction.StartStream -> onNavigateToStreamSetup()
                else -> viewModel.onAction(action)
            }
        },
        onSurfaceChanged = viewModel::setPreviewSurface,
    )
}
