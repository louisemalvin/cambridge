package dev.mobilewebcam.sender.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.value
import dev.mobilewebcam.sender.feature.settings.components.CodecSelector
import dev.mobilewebcam.sender.feature.settings.components.VideoProfileSelector
import dev.mobilewebcam.sender.feature.webcam.components.CameraLensControls
import dev.mobilewebcam.sender.feature.webcam.components.CameraStabilizationControls
import dev.mobilewebcam.sender.feature.webcam.components.CameraZoomControls

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    state: SettingsUiState,
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
                    options = state.codecOptions,
                    onSelected = { key -> onAction(SenderScreenAction.CodecSelected(key)) },
                )
            }
            item {
                VideoProfileSelector(
                    options = state.profileOptions,
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
            if (state.hasConfiguredReceiver) {
                item {
                    TextButton(
                        onClick = { onAction(SenderScreenAction.ForgetReceiver) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.forget_receiver))
                    }
                }
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
private fun SettingsConnectionDetails(state: SettingsUiState) {
    val receiver = state.receiverName ?: UiText.Resource(R.string.not_connected)
    val status = state.connectionStatus ?: UiText.Resource(R.string.not_connected)
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

private const val SETTINGS_HORIZONTAL_PADDING = 16
private const val SETTINGS_TOP_PADDING = 8
private const val SETTINGS_BOTTOM_PADDING = 32
private const val SETTINGS_SECTION_TOP_PADDING = 16
private const val SETTINGS_SECTION_BOTTOM_PADDING = 8
private const val SETTINGS_STOP_TOP_PADDING = 16
