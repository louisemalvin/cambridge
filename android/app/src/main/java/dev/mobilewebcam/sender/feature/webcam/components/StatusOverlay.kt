package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.feature.webcam.WebcamUiState
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.value

@Composable
fun PreviewStatusOverlay(
    state: WebcamUiState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        state.sessionOrientation?.let { orientation ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(ORIENTATION_BADGE_PADDING.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = STATUS_CARD_ALPHA),
            ) {
                Text(
                    text = orientation.value(),
                    modifier = Modifier.padding(ORIENTATION_BADGE_CONTENT_PADDING.dp),
                )
            }
        }
        val showCenterCard = !state.preview.isLive || !state.cameraPermissionGranted

        if (showCenterCard) {
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
}

@Composable
private fun WaitingCard(
    state: WebcamUiState,
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
        shape = MaterialTheme.shapes.medium,
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

private const val WAITING_CARD_PADDING = 16
private const val WAITING_CARD_CONTENT_PADDING = 20
private const val WAITING_CARD_ITEM_SPACING = 8
private const val STATUS_CARD_CORNER_RADIUS = 16
private const val STATUS_CARD_ALPHA = 0.92f
private const val STATUS_CARD_PADDING = 16
private const val ORIENTATION_BADGE_PADDING = 16
private const val ORIENTATION_BADGE_CONTENT_PADDING = 8
