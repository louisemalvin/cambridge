package dev.cambridge.sender.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.cambridge.sender.R
import dev.cambridge.sender.app.navigation.AppDestination
import dev.cambridge.sender.app.navigation.AppNavigation
import dev.cambridge.sender.app.navigation.rememberAppBackStack
import dev.cambridge.sender.app.permission.CameraPermissionTracker
import dev.cambridge.sender.app.startup.StartupStateResolver
import dev.cambridge.sender.connection.SenderConnectionCoordinator
import dev.cambridge.sender.feature.webcam.overlays.EndStreamConfirmationDialog
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.isSessionActive
import dev.cambridge.sender.model.requiresStopConfirmation
import kotlinx.coroutines.launch

@Composable
fun SenderApp(
    connectionCoordinator: SenderConnectionCoordinator,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val streamState by connectionCoordinator.streamState.collectAsState()
    val permissionTracker = remember(context) { CameraPermissionTracker(context) }
    var cameraPermission by remember {
        mutableStateOf(permissionTracker.read(activity))
    }
    var permissionCleanupRequested by rememberSaveable { mutableStateOf(false) }
    val latestStreamState by rememberUpdatedState(streamState)
    val initialDestination = StartupStateResolver(
        hasCameraPermission = cameraPermission.isGranted,
        hasActiveStream = streamState.isSessionActive,
    ).resolveInitialDestination()
    val backStack = rememberAppBackStack(initialDestination)
    val coroutineScope = rememberCoroutineScope()
    var isEndStreamConfirmationVisible by rememberSaveable { mutableStateOf(false) }
    val diagnosticsClipboardLabel = stringResource(R.string.diagnostics_clipboard_label)
    val errorDetailsCopiedMessage = stringResource(R.string.error_details_copied)

    fun refreshCameraPermission() {
        cameraPermission = permissionTracker.read(activity)
    }

    fun openCameraPermissionSettings() {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            },
        )
    }

    DisposableEffect(lifecycleOwner, permissionTracker) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshCameraPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(cameraPermission.isGranted) {
        if (cameraPermission.isGranted) {
            permissionCleanupRequested = false
        } else if (!permissionCleanupRequested) {
            permissionCleanupRequested = true
            if (latestStreamState.isSessionActive) {
                connectionCoordinator.stop()
            }
        }
    }

    LaunchedEffect(cameraPermission, streamState, backStack.current) {
        val permissionFailure = (streamState as? StreamState.Failed)?.failure ==
            StreamFailure.CameraPermissionDenied
        if (permissionFailure) refreshCameraPermission()
        val streamingState = streamState as? StreamState.Streaming

        when {
            !cameraPermission.isGranted -> backStack.replaceWithCameraPermission()
            streamingState != null && backStack.current == AppDestination.StreamSetup -> {
                val orientation = streamingState.session.sessionTransform
                    ?.displayOrientation
                    ?.rotationDegrees
                    ?.let(StreamOrientation::fromDisplayRotation)
                orientation?.let { activity?.lockStreamingOrientation(it) }
                backStack.navigateTo(AppDestination.Webcam)
            }
            !streamState.isSessionActive && backStack.current == AppDestination.Webcam ->
                backStack.replaceWithStreamSetup()
            !streamState.isSessionActive && backStack.current == AppDestination.Settings ->
                backStack.replaceWithStreamSetup()
            backStack.current == AppDestination.CameraPermission ->
                backStack.replaceWithStreamSetup()
        }
    }

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
                cameraPermission = cameraPermission,
                onPermissionRequestStarted = permissionTracker::recordRequest,
                onPermissionResult = { granted ->
                    permissionTracker.recordResult(granted)
                    refreshCameraPermission()
                },
                onOpenCameraPermissionSettings = ::openCameraPermissionSettings,
                onCameraPermissionLost = ::refreshCameraPermission,
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
