package dev.mobilewebcam.sender.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobilewebcam.sender.MobileWebcamApplication
import dev.mobilewebcam.sender.camera.DisplayOrientation
import dev.mobilewebcam.sender.camera.VideoPreviewLayout
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.ui.connect.ConnectScreen
import dev.mobilewebcam.sender.ui.components.CameraPreview
import dev.mobilewebcam.sender.ui.streaming.StreamingScreen

@Composable
fun SenderApp() {
    val context = LocalContext.current
    val application = context.applicationContext as MobileWebcamApplication
    val viewModel: SenderViewModel = viewModel(
        factory = SenderViewModelFactory(application),
    )
    val uiState by viewModel.uiState.collectAsState()
    val configuration = LocalConfiguration.current
    val previewOrientation = DisplayOrientation.fromPortraitFlag(
        configuration.orientation == Configuration.ORIENTATION_PORTRAIT,
    )
    val previewLayout = VideoPreviewLayout.forProfile(
        uiState.previewProfile(),
        previewOrientation,
    )
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
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

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                CameraPreview(
                    orientation = previewOrientation,
                    zoomState = uiState.cameraInteraction,
                    onSurfaceChanged = viewModel::setPreviewSurface,
                    onZoomRatioChanged = viewModel::setZoomRatio,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(previewLayout.aspectRatio),
                )
                when (uiState.streamState) {
                    is StreamState.Preparing,
                    is StreamState.Starting,
                    is StreamState.Streaming,
                    StreamState.Stopping -> StreamingScreen(
                        state = uiState,
                        onStop = viewModel::stop,
                        onZoomRatioChanged = viewModel::setZoomRatio,
                        onResetZoom = viewModel::resetZoom,
                        onStabilizationEnabledChanged = viewModel::setStabilizationEnabled,
                        onPhysicalLensSelected = viewModel::selectPhysicalLens,
                    )
                    else -> ConnectScreen(
                        state = uiState,
                        onCodecPreferenceChanged = viewModel::updateCodecPreference,
                        onProfileChanged = viewModel::updateProfile,
                        onRequestCameraPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onApprove = viewModel::approvePending,
                        onReject = viewModel::rejectPending,
                        onCopyError = {
                            val failedState = uiState.streamState as? StreamState.Failed
                            val details = uiState.failureDetails
                                ?: failedState?.let { failure ->
                                    buildFailureDiagnostics(uiState, failure.failure, null)
                                }
                            if (details != null) {
                                val clipboard = context.getSystemService(
                                    Context.CLIPBOARD_SERVICE,
                                ) as ClipboardManager
                                clipboard.setPrimaryClip(
                                    ClipData.newPlainText("Mobile Webcam error", details),
                                )
                                Toast.makeText(
                                    context,
                                    "Error details copied",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun SenderUiState.previewProfile(): VideoProfile = when (val state = streamState) {
    is StreamState.Preparing -> state.profile
    is StreamState.Starting -> state.session.profile
    is StreamState.Streaming -> state.session.profile
    else -> profile
}

private class SenderViewModelFactory(
    private val application: MobileWebcamApplication,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        check(modelClass.isAssignableFrom(SenderViewModel::class.java))
        return SenderViewModel(
            coordinator = application.connectionCoordinator,
            cameraController = application.cameraController,
        ) as T
    }
}
