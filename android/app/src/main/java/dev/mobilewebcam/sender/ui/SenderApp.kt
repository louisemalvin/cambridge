package dev.mobilewebcam.sender.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobilewebcam.sender.MobileWebcamApplication
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.ui.connect.ConnectScreen
import dev.mobilewebcam.sender.ui.streaming.StreamingScreen
import dev.mobilewebcam.sender.ui.components.CameraPreview

@Composable
fun SenderApp() {
    val context = LocalContext.current
    val application = context.applicationContext as MobileWebcamApplication
    val viewModel: SenderViewModel = viewModel(
        factory = SenderViewModelFactory(application),
    )
    val uiState by viewModel.uiState.collectAsState()
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
            Column(modifier = Modifier.fillMaxSize()) {
                CameraPreview(
                    onSurfaceChanged = viewModel::setPreviewSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    when (uiState.streamState) {
                        is StreamState.Preparing,
                        is StreamState.Starting,
                        is StreamState.Streaming,
                        StreamState.Stopping -> StreamingScreen(
                            state = uiState,
                            onStop = viewModel::stop,
                        )
                        else -> ConnectScreen(
                            state = uiState,
                            onReceiverHostChanged = viewModel::updateReceiverHost,
                            onControlPortChanged = viewModel::updateControlPort,
                            onCodecPreferenceChanged = viewModel::updateCodecPreference,
                            onProfileChanged = viewModel::updateProfile,
                            onRequestCameraPermission = {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            onStart = viewModel::start,
                        )
                    }
                }
            }
        }
    }
}

private class SenderViewModelFactory(
    private val application: MobileWebcamApplication,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        check(modelClass.isAssignableFrom(SenderViewModel::class.java))
        return SenderViewModel(
            controller = application.sessionController,
            networkInformationProvider = application.networkInformationProvider,
        ) as T
    }
}
