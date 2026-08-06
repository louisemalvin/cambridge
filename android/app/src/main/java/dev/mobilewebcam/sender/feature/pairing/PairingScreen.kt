package dev.mobilewebcam.sender.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.value

@Composable
fun PairingScreen(
    state: PairingUiState,
    computerName: String,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(CARD_PADDING.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(CARD_CONTENT_PADDING.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CARD_ITEM_SPACING.dp),
                ) {
                    Text(
                        text = computerName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when (state) {
                        PairingUiState.Idle -> Text(
                            text = stringResource(R.string.computer_ready),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is PairingUiState.Searching -> Text(
                            text = state.message.value(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is PairingUiState.Connecting -> Text(
                            text = state.message.value(),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is PairingUiState.Connected -> Text(
                            text = stringResource(R.string.computer_connected),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        is PairingUiState.Failed -> Text(
                            text = state.message.value(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(
                        onClick = { onAction(SenderScreenAction.OpenStreamSetup) },
                        enabled = state !is PairingUiState.Connecting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.setup_stream))
                    }
                }
            }
        }
    }
}

private const val CARD_PADDING = 16
private const val CARD_CONTENT_PADDING = 20
private const val CARD_ITEM_SPACING = 12
