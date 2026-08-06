package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness6
import androidx.compose.material.icons.outlined.Brightness7
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.theme.MobileWebcamTheme

@Composable
fun PreviewActions(
    isScreenDimmed: Boolean,
    connection: ConnectionUiState,
    isLandscape: Boolean,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimContentDescription = if (isScreenDimmed) {
        stringResource(R.string.brighten_screen)
    } else {
        stringResource(R.string.dim_screen)
    }
    val settingsContentDescription = stringResource(R.string.settings)
    val zoomContentDescription = stringResource(R.string.zoom)
    val stopContentDescription = stringResource(R.string.stop_stream)
    val startContentDescription = stringResource(R.string.setup_stream)

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
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
                    isScreenDimmed = isScreenDimmed,
                    connection = connection,
                    dimContentDescription = dimContentDescription,
                    zoomContentDescription = zoomContentDescription,
                    settingsContentDescription = settingsContentDescription,
                    stopContentDescription = stopContentDescription,
                    startContentDescription = startContentDescription,
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
                    isScreenDimmed = isScreenDimmed,
                    connection = connection,
                    dimContentDescription = dimContentDescription,
                    zoomContentDescription = zoomContentDescription,
                    settingsContentDescription = settingsContentDescription,
                    stopContentDescription = stopContentDescription,
                    startContentDescription = startContentDescription,
                    onAction = onAction,
                )
            }
        }
    }
}

@Composable
private fun PreviewActionButtons(
    isScreenDimmed: Boolean,
    connection: ConnectionUiState,
    dimContentDescription: String,
    zoomContentDescription: String,
    settingsContentDescription: String,
    stopContentDescription: String,
    startContentDescription: String,
    onAction: (SenderScreenAction) -> Unit,
) {
    PreviewActionButton(
        icon = if (isScreenDimmed) {
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
    if (connection == ConnectionUiState.Waiting || connection is ConnectionUiState.Failed) {
        PreviewActionButton(
            icon = Icons.Outlined.PlayArrow,
            contentDescription = startContentDescription,
            onClick = { onAction(SenderScreenAction.StartStream) },
        )
    }
    if (connection is ConnectionUiState.Streaming || connection is ConnectionUiState.Connecting ||
        connection == ConnectionUiState.Stopping
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
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
        )
    }
}

@Preview(name = "Portrait Actions")
@Composable
private fun PreviewActionsPortraitPreview() {
    MobileWebcamTheme {
        PreviewActions(
            isScreenDimmed = false,
            connection = ConnectionUiState.Waiting,
            isLandscape = false,
            onAction = {},
        )
    }
}

private const val ACTION_TOOLBAR_ALPHA = 0.92f
private const val ACTION_TOOLBAR_TONAL_ELEVATION = 3
private const val ACTION_TOOLBAR_PADDING = 8
private const val ACTION_SPACING = 8
