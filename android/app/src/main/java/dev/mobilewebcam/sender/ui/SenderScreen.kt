package dev.mobilewebcam.sender.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.ui.components.CameraLensControls
import dev.mobilewebcam.sender.ui.components.CameraPreview
import dev.mobilewebcam.sender.ui.components.CameraStabilizationControls
import dev.mobilewebcam.sender.ui.components.CameraZoomControls
import dev.mobilewebcam.sender.ui.components.CodecSelector
import dev.mobilewebcam.sender.ui.components.VideoProfileSelector
import dev.mobilewebcam.sender.ui.model.ConnectionUiState
import dev.mobilewebcam.sender.ui.model.PreviewOrientation
import dev.mobilewebcam.sender.ui.model.PreviewViewportCalculator
import dev.mobilewebcam.sender.ui.model.SenderDialogUiState
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.SenderScreenState
import dev.mobilewebcam.sender.ui.model.UiText
import dev.mobilewebcam.sender.ui.model.value

@Composable
fun SenderScreen(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val orientation = when (LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> PreviewOrientation.PORTRAIT
        else -> PreviewOrientation.LANDSCAPE
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Black,
    ) { contentPadding ->
        PreviewStage(
            state = state,
            orientation = orientation,
            onAction = onAction,
            onSurfaceChanged = onSurfaceChanged,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        )
    }

    if (state.isSettingsSheetOpen) {
        SettingsSheet(
            state = state,
            onAction = onAction,
        )
    }

    state.dialog?.let { dialog ->
        SenderDialog(
            dialog = dialog,
            onAction = onAction,
        )
    }
}

@Composable
private fun PreviewStage(
    state: SenderScreenState,
    orientation: PreviewOrientation,
    onAction: (SenderScreenAction) -> Unit,
    onSurfaceChanged: (CameraPreviewSurface?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayAspectRatio = if (orientation.isPortrait) {
        state.preview.landscapeAspectRatio.reciprocal()
    } else {
        state.preview.landscapeAspectRatio
    }

    BoxWithConstraints(
        modifier = modifier.background(Color.Black),
    ) {
        val viewport = PreviewViewportCalculator.fit(
            containerWidth = maxWidth.value,
            containerHeight = maxHeight.value,
            aspectRatio = displayAspectRatio,
        )
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(viewport.width.dp, viewport.height.dp)
                    .align(Alignment.Center),
            ) {
                CameraPreview(
                    orientation = orientation,
                    zoomState = state.camera.zoom,
                    onSurfaceChanged = onSurfaceChanged,
                    onZoomRatioChanged = { ratio ->
                        onAction(SenderScreenAction.ZoomChanged(ratio))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (state.isScreenDimmed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = SCREEN_DIM_ALPHA)),
                )
            }

            PreviewStatusOverlay(
                state = state,
                onAction = onAction,
                modifier = Modifier.fillMaxSize(),
            )

            if (state.isZoomTrayOpen) {
                ZoomTray(
                    state = state,
                    onAction = onAction,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = BOTTOM_ACTIONS_PADDING.dp),
                )
            }

            PreviewActions(
                state = state,
                onAction = onAction,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(BOTTOM_ACTIONS_PADDING.dp),
            )
        }
    }
}

@Composable
private fun PreviewStatusOverlay(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        ConnectionChip(
            state = state.connection,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(TOP_STATUS_PADDING.dp),
        )

        when (val connection = state.connection) {
            ConnectionUiState.Waiting -> WaitingCard(
                state = state,
                onAction = onAction,
                modifier = Modifier.align(Alignment.Center),
            )
            is ConnectionUiState.Connecting -> StatusCard(
                message = connection.status,
                modifier = Modifier.align(Alignment.Center),
            )
            ConnectionUiState.Stopping -> StatusCard(
                message = UiText.Resource(R.string.stopping_stream),
                modifier = Modifier.align(Alignment.Center),
            )
            is ConnectionUiState.Failed -> FailureCard(
                message = connection.message,
                onOpenSettings = { onAction(SenderScreenAction.OpenSettings) },
                modifier = Modifier.align(Alignment.Center),
            )
            is ConnectionUiState.Streaming -> Unit
        }
    }
}

@Composable
private fun ConnectionChip(
    state: ConnectionUiState,
    modifier: Modifier = Modifier,
) {
    val label = when (state) {
        ConnectionUiState.Waiting -> UiText.Resource(R.string.waiting_for_connection)
        is ConnectionUiState.Connecting -> state.status
        is ConnectionUiState.Streaming -> state.receiverName
            ?: UiText.Resource(R.string.connected_to_receiver, listOf("receiver"))
        ConnectionUiState.Stopping -> UiText.Resource(R.string.stopping_stream)
        is ConnectionUiState.Failed -> state.message
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(STATUS_CHIP_CORNER_RADIUS.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = STATUS_CHIP_ALPHA),
    ) {
        Text(
            text = label.value(),
            modifier = Modifier.padding(STATUS_CHIP_PADDING.dp),
        )
    }
}

@Composable
private fun WaitingCard(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.padding(WAITING_CARD_PADDING.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(
            modifier = Modifier.padding(WAITING_CARD_CONTENT_PADDING.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WAITING_CARD_ITEM_SPACING.dp),
        ) {
            Text(
                text = stringResource(R.string.waiting_for_connection),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.waiting_for_connection_support),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.cameraPermissionGranted) {
                OutlinedButton(onClick = { onAction(SenderScreenAction.OpenPermissionDialog) }) {
                    Text(stringResource(R.string.allow_camera_access))
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    message: UiText,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(STATUS_CARD_CORNER_RADIUS.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = STATUS_CARD_ALPHA),
    ) {
        Text(
            text = message.value(),
            modifier = Modifier.padding(STATUS_CARD_PADDING.dp),
        )
    }
}

@Composable
private fun FailureCard(
    message: UiText,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(modifier = modifier.padding(WAITING_CARD_PADDING.dp)) {
        Column(
            modifier = Modifier.padding(WAITING_CARD_CONTENT_PADDING.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(WAITING_CARD_ITEM_SPACING.dp),
        ) {
            Text(text = message.value(), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.settings))
            }
        }
    }
}

@Composable
private fun PreviewActions(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimContentDescription = if (state.isScreenDimmed) {
        stringResource(R.string.brighten_screen)
    } else {
        stringResource(R.string.dim_screen)
    }
    val settingsContentDescription = stringResource(R.string.settings)
    val zoomContentDescription = stringResource(R.string.zoom)
    val stopContentDescription = stringResource(R.string.stop_stream)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ACTION_SPACING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = { onAction(SenderScreenAction.ToggleScreenDimmed) },
            modifier = Modifier.semantics {
                contentDescription = dimContentDescription
            },
        ) {
            Icon(
                imageVector = if (state.isScreenDimmed) {
                    Icons.Outlined.Brightness7
                } else {
                    Icons.Outlined.Brightness6
                },
                contentDescription = null,
            )
        }
        FilledTonalIconButton(
            onClick = { onAction(SenderScreenAction.ToggleZoomTray) },
            modifier = Modifier.semantics {
                contentDescription = zoomContentDescription
            },
        ) {
            Icon(Icons.Outlined.ZoomIn, contentDescription = null)
        }
        FilledTonalIconButton(
            onClick = { onAction(SenderScreenAction.OpenSettings) },
            modifier = Modifier.semantics {
                contentDescription = settingsContentDescription
            },
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null)
        }
        if (state.connection is ConnectionUiState.Streaming ||
            state.connection is ConnectionUiState.Connecting
        ) {
            FilledTonalIconButton(
                onClick = { onAction(SenderScreenAction.StopStream) },
                modifier = Modifier.semantics {
                    contentDescription = stopContentDescription
                },
            ) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null)
            }
        }
    }
}

@Composable
private fun ZoomTray(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ZOOM_TRAY_HORIZONTAL_PADDING.dp),
        shape = RoundedCornerShape(ZOOM_TRAY_CORNER_RADIUS.dp),
        tonalElevation = ZOOM_TRAY_TONAL_ELEVATION.dp,
    ) {
        CameraZoomControls(
            state = state.camera.zoom,
            onZoomRatioChanged = { ratio -> onAction(SenderScreenAction.ZoomChanged(ratio)) },
            onResetZoom = { onAction(SenderScreenAction.ResetZoom) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsSheet(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onAction(SenderScreenAction.CloseSettings) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(SETTINGS_CONTENT_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(SETTINGS_ITEM_SPACING.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(stringResource(R.string.camera), style = MaterialTheme.typography.titleMedium)
            CameraLensControls(
                options = state.camera.lensOptions,
                onLensSelected = { key -> onAction(SenderScreenAction.LensSelected(key)) },
            )
            CameraStabilizationControls(
                state = state.camera.stabilization,
                onStabilizationEnabledChanged = { enabled ->
                    onAction(SenderScreenAction.StabilizationChanged(enabled))
                },
            )
            CameraZoomControls(
                state = state.camera.zoom,
                onZoomRatioChanged = { ratio -> onAction(SenderScreenAction.ZoomChanged(ratio)) },
                onResetZoom = { onAction(SenderScreenAction.ResetZoom) },
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.stream_defaults),
                style = MaterialTheme.typography.titleMedium,
            )
            CodecSelector(
                options = state.settings.codecOptions,
                onSelected = { key -> onAction(SenderScreenAction.CodecSelected(key)) },
            )
            VideoProfileSelector(
                options = state.settings.profileOptions,
                onSelected = { key -> onAction(SenderScreenAction.ProfileSelected(key)) },
            )
            HorizontalDivider()
            Text(
                text = stringResource(R.string.connection),
                style = MaterialTheme.typography.titleMedium,
            )
            SettingsConnectionDetails(state)
            state.validationMessage?.let { validationMessage ->
                Text(validationMessage.value(), color = MaterialTheme.colorScheme.error)
            }
            state.failureDiagnostics?.let {
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.diagnostics),
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedButton(onClick = { onAction(SenderScreenAction.CopyDiagnostics) }) {
                    Text(stringResource(R.string.copy_error_details))
                }
            }
            if (state.connection is ConnectionUiState.Streaming ||
                state.connection is ConnectionUiState.Connecting
            ) {
                Button(
                    onClick = { onAction(SenderScreenAction.StopStream) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.stop_stream))
                }
            }
            Spacer(Modifier.height(SETTINGS_BOTTOM_SPACER.dp))
        }
    }
}

@Composable
private fun SettingsConnectionDetails(state: SenderScreenState) {
    val receiver = state.settings.receiverName ?: UiText.Resource(R.string.not_connected)
    val status = state.settings.connectionStatus ?: UiText.Resource(R.string.not_connected)
    ListItem(
        headlineContent = { Text(stringResource(R.string.receiver)) },
        supportingContent = { Text(receiver.value()) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.stream_status)) },
        supportingContent = { Text(status.value()) },
    )
}

@Composable
private fun SenderDialog(
    dialog: SenderDialogUiState,
    onAction: (SenderScreenAction) -> Unit,
) {
    when (dialog) {
        is SenderDialogUiState.CameraPermission -> AlertDialog(
            onDismissRequest = { onAction(SenderScreenAction.DismissPermissionDialog) },
            title = { Text(dialog.title.value()) },
            text = { Text(dialog.message.value()) },
            confirmButton = {
                Button(onClick = { onAction(SenderScreenAction.RequestCameraPermission) }) {
                    Text(stringResource(R.string.allow_camera_access))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(SenderScreenAction.DismissPermissionDialog) }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
        is SenderDialogUiState.PendingApproval -> AlertDialog(
            onDismissRequest = { onAction(SenderScreenAction.RejectPending) },
            title = { Text(stringResource(R.string.approve_this_computer)) },
            text = {
                Text(
                    stringResource(
                        R.string.pending_approval_message,
                        dialog.receiverName.value(),
                    ),
                )
            },
            confirmButton = {
                Button(onClick = { onAction(SenderScreenAction.ApprovePending) }) {
                    Text(stringResource(R.string.approve_this_computer))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(SenderScreenAction.RejectPending) }) {
                    Text(stringResource(R.string.reject))
                }
            },
        )
    }
}

private fun Float.reciprocal(): Float = UNIT_RATIO / this

private const val UNIT_RATIO = 1.0f
private const val SCREEN_DIM_ALPHA = 0.72f
private const val TOP_STATUS_PADDING = 16
private const val STATUS_CHIP_CORNER_RADIUS = 20
private const val STATUS_CHIP_ALPHA = 0.92f
private const val STATUS_CHIP_PADDING = 10
private const val BOTTOM_ACTIONS_PADDING = 16
private const val ACTION_SPACING = 12
private const val WAITING_CARD_PADDING = 16
private const val WAITING_CARD_CONTENT_PADDING = 20
private const val WAITING_CARD_ITEM_SPACING = 8
private const val STATUS_CARD_CORNER_RADIUS = 16
private const val STATUS_CARD_ALPHA = 0.92f
private const val STATUS_CARD_PADDING = 16
private const val ZOOM_TRAY_HORIZONTAL_PADDING = 16
private const val ZOOM_TRAY_CORNER_RADIUS = 20
private const val ZOOM_TRAY_TONAL_ELEVATION = 3
private const val SETTINGS_CONTENT_PADDING = 20
private const val SETTINGS_ITEM_SPACING = 12
private const val SETTINGS_BOTTOM_SPACER = 16
