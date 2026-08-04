package dev.mobilewebcam.sender.feature.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.connection.discovery.DiscoveredReceiver
import dev.mobilewebcam.sender.connection.discovery.ReceiverDiscoveryState
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.value
import dev.mobilewebcam.sender.model.ReceiverEndpoint

@Composable
fun PairingScreen(
    state: PairingUiState,
    receiverOrigin: ReceiverOriginDraft,
    receiverOriginError: String?,
    discoveryState: ReceiverDiscoveryState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var manualEntryVisible by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(CARD_PADDING.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(CARD_CONTENT_PADDING.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CARD_ITEM_SPACING.dp),
                ) {
                    when (state) {
                        PairingUiState.Idle,
                        is PairingUiState.Searching -> {
                            Text(
                                text = stringResource(R.string.waiting_for_receiver),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = stringResource(R.string.waiting_for_receiver_support),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is PairingUiState.Connecting -> {
                            Text(
                                text = state.message.value(),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        is PairingUiState.Connected -> {
                            Text(
                                text = stringResource(R.string.connected_to_receiver, state.receiverName.value()),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        is PairingUiState.Failed -> {
                            Text(
                                text = state.message.value(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    NearbyReceivers(
                        state = discoveryState,
                        onSelect = { endpoint ->
                            manualEntryVisible = true
                            onAction(SenderScreenAction.DiscoveredReceiverSelected(endpoint))
                        },
                    )
                    if (manualEntryVisible) {
                        HorizontalDivider()
                        ReceiverOriginForm(
                            origin = receiverOrigin,
                            error = receiverOriginError,
                            enabled = state !is PairingUiState.Connecting,
                            onAction = onAction,
                        )
                    } else {
                        OutlinedButton(
                            onClick = { manualEntryVisible = true },
                            enabled = state !is PairingUiState.Connecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.connect_to_receiver))
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun ReceiverOriginForm(
    origin: ReceiverOriginDraft,
    error: String?,
    enabled: Boolean,
    onAction: (SenderScreenAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FORM_FIELD_SPACING.dp),
    ) {
        Text(
            text = stringResource(R.string.manual_connection),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.receiver_origin_support),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = origin.name,
            onValueChange = { onAction(SenderScreenAction.ReceiverNameChanged(it)) },
            label = { Text(stringResource(R.string.receiver_name_input)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = origin.host,
            onValueChange = { onAction(SenderScreenAction.ReceiverHostChanged(it)) },
            label = { Text(stringResource(R.string.receiver_host_input)) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = origin.controlPort,
            onValueChange = { onAction(SenderScreenAction.ReceiverControlPortChanged(it)) },
            label = { Text(stringResource(R.string.receiver_control_port_input)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = origin.token,
            onValueChange = { onAction(SenderScreenAction.ReceiverTokenChanged(it)) },
            label = { Text(stringResource(R.string.receiver_token_input)) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = { onAction(SenderScreenAction.ConnectReceiver) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.connect))
        }
    }
}

@Composable
private fun NearbyReceivers(
    state: ReceiverDiscoveryState,
    onSelect: (ReceiverEndpoint) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FORM_FIELD_SPACING.dp),
    ) {
        Text(
            text = stringResource(R.string.nearby_receivers),
            style = MaterialTheme.typography.titleSmall,
        )
        when (state) {
            ReceiverDiscoveryState.Idle,
            ReceiverDiscoveryState.Searching -> {
                Text(
                    text = stringResource(R.string.searching_for_receivers),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ReceiverDiscoveryState.Available -> {
                state.receivers.forEach { receiver ->
                    DiscoveredReceiverRow(receiver, onSelect)
                }
            }
            is ReceiverDiscoveryState.Failed -> {
                Text(
                    text = stringResource(R.string.discovery_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun DiscoveredReceiverRow(
    receiver: DiscoveredReceiver,
    onSelect: (ReceiverEndpoint) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(RECEIVER_ROW_TEXT_WEIGHT)) {
            Text(
                text = receiver.endpoint.displayName,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (receiver.endpoint.authenticationRequired) {
                    stringResource(
                        R.string.receiver_requires_token,
                        receiver.endpoint.controlBaseUrl,
                    )
                } else {
                    receiver.endpoint.controlBaseUrl
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { onSelect(receiver.endpoint) }) {
            Text(stringResource(R.string.select_receiver))
        }
    }
}

private const val CARD_PADDING = 16
private const val CARD_CONTENT_PADDING = 20
private const val CARD_ITEM_SPACING = 8
private const val FORM_FIELD_SPACING = 8
private const val RECEIVER_ROW_TEXT_WEIGHT = 1.0f
