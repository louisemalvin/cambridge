package dev.mobilewebcam.sender.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
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
    val orientation = when (LocalConfiguration.current.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> PreviewOrientation.PORTRAIT
        else -> PreviewOrientation.LANDSCAPE
    }

    BackHandler(enabled = state.isSettingsOpen && state.dialog == null) {
        onAction(SenderScreenAction.CloseSettings)
    }

    if (state.isSettingsOpen) {
        SettingsScreen(
            state = state,
            onAction = onAction,
        )
    } else {
        PreviewScreen(
            state = state,
            orientation = orientation,
            onAction = onAction,
            onSurfaceChanged = onSurfaceChanged,
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
        modifier = modifier,
    ) {
        val isLandscape = maxWidth > maxHeight
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
                        .then(
                            if (isLandscape) {
                                Modifier.systemBarsPadding()
                            } else {
                                Modifier.navigationBarsPadding()
                            },
                        )
                        .padding(bottom = ZOOM_TRAY_BOTTOM_PADDING.dp),
                )
            }

            PreviewActions(
                state = state,
                isLandscape = isLandscape,
                onAction = onAction,
                modifier = Modifier
                    .align(
                        if (isLandscape) Alignment.CenterEnd else Alignment.BottomCenter,
                    )
                    .then(
                        if (isLandscape) {
                            Modifier.systemBarsPadding()
                        } else {
                            Modifier.navigationBarsPadding()
                        },
                    )
                    .padding(
                        end = if (isLandscape) {
                            ACTION_TOOLBAR_END_PADDING.dp
                        } else {
                            NO_PADDING_DP.dp
                        },
                        bottom = if (isLandscape) {
                            NO_PADDING_DP.dp
                        } else {
                            ACTION_TOOLBAR_BOTTOM_PADDING.dp
                        },
                    ),
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            STATUS_CHIP_CORNER_RADIUS.dp,
        ),
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(STATUS_CARD_CORNER_RADIUS.dp),
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
    isLandscape: Boolean,
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

    Surface(
        modifier = modifier,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            ACTION_TOOLBAR_CORNER_RADIUS.dp,
        ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = ACTION_TOOLBAR_ALPHA),
        tonalElevation = ACTION_TOOLBAR_TONAL_ELEVATION.dp,
    ) {
        val contentModifier = Modifier.padding(ACTION_TOOLBAR_PADDING.dp)
        if (isLandscape) {
            Column(
                modifier = contentModifier,
                verticalArrangement = Arrangement.spacedBy(ACTION_SPACING.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PreviewActionButtons(
                    state = state,
                    dimContentDescription = dimContentDescription,
                    zoomContentDescription = zoomContentDescription,
                    settingsContentDescription = settingsContentDescription,
                    stopContentDescription = stopContentDescription,
                    onAction = onAction,
                )
            }
        } else {
            Row(
                modifier = contentModifier,
                horizontalArrangement = Arrangement.spacedBy(ACTION_SPACING.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewActionButtons(
                    state = state,
                    dimContentDescription = dimContentDescription,
                    zoomContentDescription = zoomContentDescription,
                    settingsContentDescription = settingsContentDescription,
                    stopContentDescription = stopContentDescription,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun PreviewActionButtons(
    state: SenderScreenState,
    dimContentDescription: String,
    zoomContentDescription: String,
    settingsContentDescription: String,
    stopContentDescription: String,
    onAction: (SenderScreenAction) -> Unit,
) {
    PreviewActionButton(
        icon = if (state.isScreenDimmed) {
            Icons.Outlined.Brightness7
        } else {
            Icons.Outlined.Brightness6
        },
        contentDescription = dimContentDescription,
        onClick = { onAction(SenderScreenAction.ToggleScreenDimmed) },
    )
    PreviewActionButton(
        icon = Icons.Outlined.ZoomIn,
        contentDescription = zoomContentDescription,
        onClick = { onAction(SenderScreenAction.ToggleZoomTray) },
    )
    PreviewActionButton(
        icon = Icons.Outlined.Settings,
        contentDescription = settingsContentDescription,
        onClick = { onAction(SenderScreenAction.OpenSettings) },
    )
    if (state.connection is ConnectionUiState.Streaming ||
        state.connection is ConnectionUiState.Connecting
    ) {
        PreviewActionButton(
            icon = Icons.Outlined.StopCircle,
            contentDescription = stopContentDescription,
            onClick = { onAction(SenderScreenAction.StopStream) },
        )
    }
}

@Composable
private fun PreviewActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledTonalIconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
        )
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
        shape = androidx.compose.foundation.shape.RoundedCornerShape(ZOOM_TRAY_CORNER_RADIUS.dp),
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
private fun SettingsScreen(
    state: SenderScreenState,
    onAction: (SenderScreenAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(
                        onClick = { onAction(SenderScreenAction.CloseSettings) },
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(
                start = SETTINGS_HORIZONTAL_PADDING.dp,
                top = SETTINGS_TOP_PADDING.dp,
                end = SETTINGS_HORIZONTAL_PADDING.dp,
                bottom = SETTINGS_BOTTOM_PADDING.dp,
            ),
        ) {
            item {
                SettingsSectionHeader(R.string.camera)
            }
            item {
                CameraLensControls(
                    options = state.camera.lensOptions,
                    onLensSelected = { key -> onAction(SenderScreenAction.LensSelected(key)) },
                )
            }
            item {
                CameraStabilizationControls(
                    state = state.camera.stabilization,
                    onStabilizationEnabledChanged = { enabled ->
                        onAction(SenderScreenAction.StabilizationChanged(enabled))
                    },
                )
            }
            item {
                CameraZoomControls(
                    state = state.camera.zoom,
                    onZoomRatioChanged = { ratio ->
                        onAction(SenderScreenAction.ZoomChanged(ratio))
                    },
                    onResetZoom = { onAction(SenderScreenAction.ResetZoom) },
                )
            }
            item {
                HorizontalDivider()
            }
            item {
                SettingsSectionHeader(R.string.stream_defaults)
            }
            item {
                CodecSelector(
                    options = state.settings.codecOptions,
                    onSelected = { key -> onAction(SenderScreenAction.CodecSelected(key)) },
                )
            }
            item {
                VideoProfileSelector(
                    options = state.settings.profileOptions,
                    onSelected = { key -> onAction(SenderScreenAction.ProfileSelected(key)) },
                )
            }
            item {
                HorizontalDivider()
            }
            item {
                SettingsSectionHeader(R.string.connection)
            }
            item {
                SettingsConnectionDetails(state)
            }
            state.validationMessage?.let { validationMessage ->
                item {
                    Text(
                        text = validationMessage.value(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.failureDiagnostics?.let {
                item {
                    HorizontalDivider()
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.diagnostics)) },
                        supportingContent = {
                            Text(stringResource(R.string.copy_error_details))
                        },
                        trailingContent = {
                            TextButton(onClick = {
                                onAction(SenderScreenAction.CopyDiagnostics)
                            }) {
                                Text(stringResource(R.string.copy))
                            }
                        },
                    )
                }
            }
            if (state.connection is ConnectionUiState.Streaming ||
                state.connection is ConnectionUiState.Connecting
            ) {
                item {
                    Button(
                        onClick = { onAction(SenderScreenAction.StopStream) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = SETTINGS_STOP_TOP_PADDING.dp),
                    ) {
                        Text(stringResource(R.string.stop_stream))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(resourceId: Int) {
    Text(
        text = stringResource(resourceId),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            top = SETTINGS_SECTION_TOP_PADDING.dp,
            bottom = SETTINGS_SECTION_BOTTOM_PADDING.dp,
        ),
    )
}

@Composable
private fun SettingsConnectionDetails(state: SenderScreenState) {
    val receiver = state.settings.receiverName ?: UiText.Resource(R.string.not_connected)
    val status = state.settings.connectionStatus ?: UiText.Resource(R.string.not_connected)
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.receiver)) },
            supportingContent = { Text(receiver.value()) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.stream_status)) },
            supportingContent = { Text(status.value()) },
        )
    }
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
private const val ACTION_TOOLBAR_END_PADDING = 16
private const val ACTION_TOOLBAR_BOTTOM_PADDING = 16
private const val ACTION_TOOLBAR_PADDING = 8
private const val ACTION_TOOLBAR_CORNER_RADIUS = 28
private const val ACTION_TOOLBAR_ALPHA = 0.92f
private const val ACTION_TOOLBAR_TONAL_ELEVATION = 3
private const val ACTION_SPACING = 8
private const val NO_PADDING_DP = 0
private const val ZOOM_TRAY_BOTTOM_PADDING = 96
private const val ZOOM_TRAY_HORIZONTAL_PADDING = 16
private const val ZOOM_TRAY_CORNER_RADIUS = 20
private const val ZOOM_TRAY_TONAL_ELEVATION = 3
private const val WAITING_CARD_PADDING = 16
private const val WAITING_CARD_CONTENT_PADDING = 20
private const val WAITING_CARD_ITEM_SPACING = 8
private const val STATUS_CARD_CORNER_RADIUS = 16
private const val STATUS_CARD_ALPHA = 0.92f
private const val STATUS_CARD_PADDING = 16
private const val SETTINGS_HORIZONTAL_PADDING = 16
private const val SETTINGS_TOP_PADDING = 8
private const val SETTINGS_BOTTOM_PADDING = 32
private const val SETTINGS_SECTION_TOP_PADDING = 16
private const val SETTINGS_SECTION_BOTTOM_PADDING = 8
private const val SETTINGS_STOP_TOP_PADDING = 16
