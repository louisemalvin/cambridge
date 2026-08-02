package dev.mobilewebcam.sender.ui

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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobilewebcam.sender.MobileWebcamApplication
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.SenderUiEffect

@Composable
fun SenderApp() {
    val context = LocalContext.current
    val application = context.applicationContext as MobileWebcamApplication
    val viewModel: SenderViewModel = viewModel(
        factory = SenderViewModelFactory(application),
    )
    val screenState by viewModel.uiState.collectAsState()
    val diagnosticsClipboardLabel = stringResource(R.string.diagnostics_clipboard_label)
    val errorDetailsCopiedMessage = stringResource(R.string.error_details_copied)
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

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            SenderScreen(
                state = screenState,
                onAction = viewModel::onAction,
                onSurfaceChanged = viewModel::setPreviewSurface,
            )
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

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
