package dev.mobilewebcam.sender.feature.setup

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.StreamPresentationMapper
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.value
import dev.mobilewebcam.sender.feature.settings.components.SettingsChoiceRow
import dev.mobilewebcam.sender.feature.webcam.components.CameraStabilizationControls

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun StreamSetupScreen(
    state: StreamSetupUiState,
    onAction: (SenderScreenAction) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val startDescription = stringResource(R.string.setup_start_stream)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stream_setup)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
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
                start = SETUP_HORIZONTAL_PADDING.dp,
                top = SETUP_TOP_PADDING.dp,
                end = SETUP_HORIZONTAL_PADDING.dp,
                bottom = SETUP_BOTTOM_PADDING.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(SETUP_ITEM_SPACING.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.stream_setup_support),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.setup_computer)) },
                    supportingContent = {
                        Text(state.receiverName?.value() ?: stringResource(R.string.computer_name))
                    },
                )
            }
            item {
                ReceiverReadiness(
                    state = state.receiverReadiness,
                    onCheckAgain = { onAction(SenderScreenAction.CheckReceiver) },
                )
            }
            item {
                SettingsChoiceRow(
                    titleResourceId = R.string.video_quality,
                    options = state.profileOptions,
                    onSelected = { profileId ->
                        onAction(SenderScreenAction.ProfileSelected(profileId))
                    },
                )
            }
            if (state.stabilization.isSupported) {
                item {
                    Text(
                        text = stringResource(R.string.camera),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                item {
                    CameraStabilizationControls(
                        state = state.stabilization,
                        onStabilizationEnabledChanged = { enabled ->
                            onAction(SenderScreenAction.StabilizationChanged(enabled))
                        },
                    )
                }
            }
            item {
                SettingsChoiceRow(
                    titleResourceId = R.string.frame_rate,
                    options = state.frameRateOptions,
                    onSelected = { fps ->
                        fps.toIntOrNull()?.let { frameRate ->
                            onAction(SenderScreenAction.FrameRateSelected(frameRate))
                        }
                    },
                )
            }
            item {
                SettingsChoiceRow(
                    titleResourceId = R.string.orientation,
                    options = state.orientationOptions,
                    onSelected = { orientationName ->
                        runCatching {
                            dev.mobilewebcam.sender.model.StreamOrientation.valueOf(orientationName)
                        }.getOrNull()?.let { orientation ->
                            onAction(SenderScreenAction.StreamOrientationSelected(orientation))
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.orientation_support),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                SessionContractSummary(state)
            }
            if (!state.selectedProfileSupported && state.receiverReadiness is ReceiverReadinessUiState.Ready) {
                item {
                    Text(
                        text = stringResource(R.string.receiver_profile_not_supported),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.connection is ConnectionUiState.Failed) {
                item {
                    Text(
                        text = state.connection.message.value(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            state.validationMessage?.let { message ->
                item {
                    Text(
                        text = message.value(),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                Button(
                    onClick = { onAction(SenderScreenAction.StartStream) },
                    enabled = state.canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = startDescription
                        },
                ) {
                    Text(stringResource(R.string.setup_start_stream))
                }
            }
        }
    }
}

@Composable
private fun ReceiverReadiness(
    state: ReceiverReadinessUiState,
    onCheckAgain: () -> Unit,
) {
    when (state) {
        ReceiverReadinessUiState.Checking -> ListItem(
            headlineContent = { Text(stringResource(R.string.receiver_status)) },
            supportingContent = { Text(stringResource(R.string.receiver_checking)) },
        )
        is ReceiverReadinessUiState.Ready -> ListItem(
            headlineContent = { Text(stringResource(R.string.receiver_found)) },
            supportingContent = {
                Text(
                    state.receiverName.value() + " · " + state.address.value(),
                )
            },
        )
        is ReceiverReadinessUiState.Unavailable -> ListItem(
            headlineContent = { Text(stringResource(R.string.receiver_not_found)) },
            supportingContent = { Text(state.message.value()) },
            trailingContent = {
                TextButton(onClick = onCheckAgain) {
                    Text(stringResource(R.string.receiver_check_again))
                }
            },
        )
    }
}

@Composable
private fun SessionContractSummary(
    state: StreamSetupUiState,
) {
    val profile = state.selectedProfile
    val quality = UiText.Resource(
        R.string.stream_plan_summary,
        listOf(
            profile.width.toString() + "×" + profile.height,
            StreamPresentationMapper.orientationLabel(state.selectedOrientation).value(),
            profile.fps,
        ),
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.session_contract)) },
        supportingContent = { Text(quality.value()) },
    )
}

private const val SETUP_HORIZONTAL_PADDING = 16
private const val SETUP_TOP_PADDING = 12
private const val SETUP_BOTTOM_PADDING = 32
private const val SETUP_ITEM_SPACING = 8
