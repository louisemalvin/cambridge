package dev.cambridge.sender.feature.setup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.core.app.ActivityCompat
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
    var cameraPermissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }
    var cameraPermissionRequestedBefore by rememberSaveable { mutableStateOf(false) }
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
            cameraPermissionPermanentlyDenied = cameraPermissionRequestedBefore &&
                activity?.let { currentActivity ->
                    !ActivityCompat.shouldShowRequestPermissionRationale(
                        currentActivity,
                        Manifest.permission.CAMERA,
                    )
                } == true
        }
        if (granted) cameraPermissionPermanentlyDenied = false
        cameraPermissionRequestedBefore = true
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    fun openCameraPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    fun requestCameraPermission(startAfterGrant: Boolean) {
        if (cameraPermissionGranted) {
            if (startAfterGrant) viewModel.onAction(SenderScreenAction.StartStream)
            return
        }
        pendingStart = startAfterGrant
        if (cameraPermissionPermanentlyDenied) {
            openCameraPermissionSettings()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(Unit) {
        activity?.unlockStreamingOrientation()
    }
    LaunchedEffect(cameraPermissionGranted) {
        if (cameraPermissionGranted) {
            viewModel.prepareCamera()
        } else {
            viewModel.clearCameraCapabilities()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = context.hasCameraPermission()
                cameraPermissionGranted = granted
                if (granted) {
                    cameraPermissionPermanentlyDenied = false
                    if (pendingStart) {
                        pendingStart = false
                        viewModel.onAction(SenderScreenAction.StartStream)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        cameraPermission = CameraPermissionUiState(
            isGranted = cameraPermissionGranted,
            isPermanentlyDenied = cameraPermissionPermanentlyDenied,
        ),
        onAction = { action ->
            when (action) {
                is SenderScreenAction.StreamOrientationSelected -> {
                    viewModel.onAction(action)
                }
                SenderScreenAction.RequestCameraPermission -> {
                    requestCameraPermission(startAfterGrant = false)
                }
                SenderScreenAction.StartStream -> {
                    requestCameraPermission(startAfterGrant = true)
                }
                else -> viewModel.onAction(action)
            }
        },
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
