package dev.cambridge.sender.feature.setup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cambridge.sender.R
import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.SenderScreenAction
import dev.cambridge.sender.app.model.StreamPresentationMapper
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.app.model.value
import dev.cambridge.sender.feature.settings.components.SettingsChoiceRow
import dev.cambridge.sender.feature.webcam.components.CameraAntiFlickerControls
import dev.cambridge.sender.feature.webcam.components.CameraStabilizationControls
import dev.cambridge.sender.media.camera.CameraStabilizationApplyStatus
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.session.VideoProfiles
import kotlin.math.roundToInt

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun StreamSetupScreen(
    state: StreamSetupUiState,
    onAction: (SenderScreenAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val startDescription = stringResource(R.string.setup_start_stream)
    val hasCameraSettings = state.stabilization.supportedModes.any { mode ->
        mode != CameraStabilizationMode.OFF
    } || state.stabilization.applyStatus == CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM ||
        state.antiFlicker.options.isNotEmpty()
    var streamSettingsExpanded by rememberSaveable { mutableStateOf(true) }
    var cameraSettingsExpanded by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.selectedProfileSupported, state.validationMessage) {
        if (!state.selectedProfileSupported && state.validationMessage != null) {
            streamSettingsExpanded = true
        }
    }
    LaunchedEffect(state.stabilization.applyStatus) {
        if (state.stabilization.applyStatus == CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM) {
            cameraSettingsExpanded = true
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                    )
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
                ReceiverReadiness(
                    state = state.receiverReadiness,
                    options = state.receiverOptions,
                    manualReceiverHost = state.manualReceiverHost,
                    manualHostError = state.manualReceiverHostError,
                    isManualInputVisible = state.isManualReceiverInputVisible,
                    onReceiverSelected = { receiverId ->
                        onAction(SenderScreenAction.ReceiverSelected(receiverId))
                    },
                    onShowManualInput = {
                        onAction(SenderScreenAction.ShowManualReceiverInput)
                    },
                    onManualReceiverHostChanged = { host ->
                        onAction(SenderScreenAction.ReceiverHostChanged(host))
                    },
                    onUseManualReceiverHost = {
                        onAction(SenderScreenAction.UseManualReceiverHost)
                    },
                    onHideManualReceiverInput = {
                        onAction(SenderScreenAction.HideManualReceiverInput)
                    },
                    onCheckAgain = {
                        onAction(SenderScreenAction.CheckReceiver)
                    },
                )
            }
            item {
                ExpandableSettingsHeader(
                    title = stringResource(R.string.stream_settings),
                    summary = streamSettingsSummary(state),
                    expanded = streamSettingsExpanded,
                    onToggle = { streamSettingsExpanded = !streamSettingsExpanded },
                )
            }
            if (streamSettingsExpanded) {
                item {
                    SegmentedChoiceRow(
                        title = stringResource(R.string.resolution),
                        options = state.resolutionOptions,
                        onSelected = { onAction(SenderScreenAction.ProfileSelected(it)) },
                    )
                }
                item {
                    SetupDropdown(
                        title = stringResource(R.string.frame_rate),
                        options = state.frameRateOptions,
                        placeholder = stringResource(R.string.frame_rate_choose),
                        onSelected = { it.toIntOrNull()?.let { fps ->
                            onAction(SenderScreenAction.FrameRateSelected(fps))
                        } },
                    )
                }
                item {
                    BitrateControl(
                        state = state.bitrate,
                        onBitrateSelected = { onAction(SenderScreenAction.BitrateSelected(it)) },
                    )
                }
                item {
                    SettingsChoiceRow(
                        titleResourceId = R.string.orientation,
                        options = state.orientationOptions,
                        onSelected = { orientationName ->
                            runCatching {
                                StreamOrientation.valueOf(orientationName)
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
            }
            if (hasCameraSettings) {
                item {
                    ExpandableSettingsHeader(
                        title = stringResource(R.string.camera_settings),
                        summary = cameraSettingsSummary(state),
                        expanded = cameraSettingsExpanded,
                        onToggle = { cameraSettingsExpanded = !cameraSettingsExpanded },
                    )
                }
                if (cameraSettingsExpanded) {
                    item {
                        CameraStabilizationControls(
                            state = state.stabilization,
                            onStabilizationModeChanged = { mode ->
                                onAction(SenderScreenAction.StabilizationModeChanged(mode))
                            },
                        )
                    }
                    item {
                        CameraAntiFlickerControls(
                            state = state.antiFlicker,
                            onModeSelected = { mode ->
                                onAction(SenderScreenAction.AntiFlickerChanged(mode))
                            },
                        )
                    }
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
                        .semantics { contentDescription = startDescription },
                ) {
                    Text(stringResource(R.string.setup_start_stream))
                }
            }
        }
    }
}

@Composable
private fun ExpandableSettingsHeader(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggleDescription = stringResource(
        if (expanded) R.string.collapse_section else R.string.expand_section,
        title,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = toggleDescription, onClick = onToggle)
            .padding(vertical = SETTINGS_HEADER_VERTICAL_PADDING.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(HEADER_TEXT_WEIGHT),
            verticalArrangement = Arrangement.spacedBy(CHOICE_LABEL_SPACING.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (!expanded && summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = toggleDescription,
        )
    }
}

@Composable
private fun streamSettingsSummary(state: StreamSetupUiState): String {
    val resolution = state.resolutionOptions
        .firstOrNull(SelectOptionUi::isSelected)
        ?.label
        ?.value()
    val frameRate = state.frameRateOptions
        .firstOrNull(SelectOptionUi::isSelected)
        ?.label
        ?.value()
    val bitrate = if (state.bitrate.isAvailable) {
        stringResource(
            R.string.bitrate_summary,
            state.bitrate.selectedBps / VideoProfiles.MEGABIT,
        )
    } else {
        null
    }
    return listOfNotNull(resolution, frameRate, bitrate).joinToString(SUMMARY_SEPARATOR)
}

@Composable
private fun cameraSettingsSummary(state: StreamSetupUiState): String {
    val stabilization = stabilizationSummary(state.stabilization.selectedMode)
    val antiFlicker = state.antiFlicker.options
        .firstOrNull(SelectOptionUi::isSelected)
        ?.label
        ?.value()
    return listOfNotNull(stabilization, antiFlicker).joinToString(SUMMARY_SEPARATOR)
}

@Composable
private fun stabilizationSummary(mode: CameraStabilizationMode): String = when (mode) {
    CameraStabilizationMode.OFF -> stringResource(R.string.off)
    CameraStabilizationMode.OPTICAL -> stringResource(R.string.stabilization_optical)
    CameraStabilizationMode.ELECTRONIC -> stringResource(R.string.stabilization_electronic)
    CameraStabilizationMode.PREVIEW -> stringResource(R.string.stabilization_preview)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SegmentedChoiceRow(
    title: String,
    options: List<SelectOptionUi>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CHOICE_LABEL_SPACING.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option.isSelected,
                    onClick = { onSelected(option.key) },
                    enabled = option.isEnabled,
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(option.label.value()) },
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SetupDropdown(
    title: String?,
    options: List<SelectOptionUi>,
    placeholder: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull(SelectOptionUi::isSelected)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CHOICE_LABEL_SPACING.dp),
    ) {
        title?.let { label ->
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selectedOption?.label?.value() ?: placeholder,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label.value()) },
                        enabled = option.isEnabled,
                        onClick = {
                            expanded = false
                            onSelected(option.key)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun BitrateControl(
    state: BitrateUiState,
    onBitrateSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isAvailable) return
    val sliderDescription = stringResource(R.string.bitrate_slider)
    val bitrateInputDescription = stringResource(R.string.bitrate_input)
    val minimumMbps = state.minimumBps / VideoProfiles.MEGABIT
    val maximumMbps = state.maximumBps / VideoProfiles.MEGABIT
    val stepMbps = state.stepBps / VideoProfiles.MEGABIT
    var sliderIndex by remember(state.selectedIndex) {
        mutableFloatStateOf(state.selectedIndex.toFloat())
    }
    var bitrateInput by remember(state.selectedBps) {
        mutableStateOf((state.selectedBps / VideoProfiles.MEGABIT).toString())
    }
    var inputHasError by remember(state.selectedBps) { mutableStateOf(false) }
    var inputHadFocus by remember { mutableStateOf(false) }
    fun commitBitrateInput() {
        val requestedMbps = bitrateInput.toIntOrNull()
        val requestedBps = requestedMbps?.toLong()?.times(VideoProfiles.MEGABIT)
        val validBps = requestedBps
            ?.takeIf { it in state.minimumBps.toLong()..state.maximumBps.toLong() }
            ?.takeIf { (it - state.minimumBps) % state.stepBps == 0L }
            ?.toInt()
        inputHasError = validBps == null
        if (validBps != null) {
            sliderIndex = ((validBps - state.minimumBps) / state.stepBps).toFloat()
            onBitrateSelected(validBps)
        }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CHOICE_LABEL_SPACING.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.bitrate),
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                value = bitrateInput,
                onValueChange = { input ->
                    if (input.all(Char::isDigit)) {
                        bitrateInput = input
                        inputHasError = false
                    }
                },
                singleLine = true,
                isError = inputHasError,
                suffix = { Text(stringResource(R.string.bitrate_unit_mbps)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { commitBitrateInput() }),
                modifier = Modifier
                    .width(BITRATE_INPUT_WIDTH.dp)
                    .onFocusChanged { focusState ->
                        if (inputHadFocus && !focusState.isFocused) commitBitrateInput()
                        inputHadFocus = focusState.isFocused
                    }
                    .semantics { contentDescription = bitrateInputDescription },
            )
        }
        Slider(
            value = sliderIndex,
            onValueChange = { value ->
                val selectedIndex = value.roundToInt().coerceIn(FIRST_SLIDER_INDEX, state.lastIndex)
                sliderIndex = selectedIndex.toFloat()
                bitrateInput = ((state.minimumBps + selectedIndex * state.stepBps) /
                    VideoProfiles.MEGABIT).toString()
                inputHasError = false
            },
            onValueChangeFinished = {
                val selectedIndex = sliderIndex.roundToInt().coerceIn(FIRST_SLIDER_INDEX, state.lastIndex)
                onBitrateSelected(state.minimumBps + selectedIndex * state.stepBps)
            },
            valueRange = FIRST_SLIDER_INDEX.toFloat()..
                state.lastIndex.coerceAtLeast(MINIMUM_SLIDER_RANGE_INDEX).toFloat(),
            steps = state.sliderSteps,
            modifier = Modifier.semantics {
                contentDescription = sliderDescription
            },
        )
        Text(
            text = if (inputHasError) {
                stringResource(R.string.bitrate_input_error, minimumMbps, maximumMbps, stepMbps)
            } else {
                stringResource(R.string.bitrate_input_support, minimumMbps, maximumMbps, stepMbps)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (inputHasError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun ReceiverReadiness(
    state: ReceiverReadinessUiState,
    options: List<SelectOptionUi>,
    manualReceiverHost: String,
    manualHostError: UiText?,
    isManualInputVisible: Boolean,
    onReceiverSelected: (String) -> Unit,
    onShowManualInput: () -> Unit,
    onManualReceiverHostChanged: (String) -> Unit,
    onUseManualReceiverHost: () -> Unit,
    onHideManualReceiverInput: () -> Unit,
    onCheckAgain: () -> Unit,
) {
    var isEditing by rememberSaveable { mutableStateOf(false) }
    val showEditor = isEditing || state !is ReceiverReadinessUiState.Ready
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(RECEIVER_FALLBACK_ITEM_SPACING.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.receiver),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(HEADER_TEXT_WEIGHT),
            )
            when {
                state is ReceiverReadinessUiState.Ready && showEditor -> IconButton(
                    onClick = {
                        isEditing = false
                        onHideManualReceiverInput()
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.receiver_change_cancel),
                    )
                }
                state is ReceiverReadinessUiState.Ready -> IconButton(
                    onClick = { isEditing = true },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.receiver_change),
                    )
                }
                state is ReceiverReadinessUiState.Unavailable -> IconButton(
                    onClick = onCheckAgain,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.receiver_retry),
                    )
                }
            }
        }
        if (!showEditor) {
            Text(
                text = stringResource(
                    R.string.receiver_ready_summary,
                    state.receiverName.value(),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = state.address.value(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (options.isNotEmpty()) {
                SetupDropdown(
                    title = null,
                    options = options,
                    placeholder = stringResource(R.string.receiver_choose),
                    onSelected = { receiverId ->
                        isEditing = false
                        onReceiverSelected(receiverId)
                    },
                )
            }
            receiverSupportText(state)?.let { supportText ->
                Text(
                    text = supportText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isManualInputVisible || state is ReceiverReadinessUiState.Unavailable) {
                OutlinedTextField(
                    value = manualReceiverHost,
                    onValueChange = onManualReceiverHostChanged,
                    label = { Text(stringResource(R.string.receiver_manual_host_label)) },
                    supportingText = {
                        Text(
                            manualHostError?.value()
                                ?: stringResource(R.string.receiver_manual_host_support),
                        )
                    },
                    isError = manualHostError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            isEditing = false
                            onUseManualReceiverHost()
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = {
                            isEditing = false
                            onUseManualReceiverHost()
                        },
                        enabled = manualReceiverHost.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.receiver_manual_host_use))
                    }
                }
            } else if (state !is ReceiverReadinessUiState.Checking) {
                TextButton(onClick = onShowManualInput) {
                    Text(stringResource(R.string.receiver_manual_host_show))
                }
            }
        }
    }
}

@Composable
private fun receiverSupportText(state: ReceiverReadinessUiState): String? = when (state) {
    ReceiverReadinessUiState.Checking -> stringResource(R.string.receiver_checking)
    ReceiverReadinessUiState.SelectionRequired -> stringResource(R.string.receiver_choose_support)
    is ReceiverReadinessUiState.Ready -> null
    is ReceiverReadinessUiState.Unavailable -> state.message.value()
}

private const val SETUP_HORIZONTAL_PADDING = 16
private const val SETUP_TOP_PADDING = 12
private const val SETUP_BOTTOM_PADDING = 32
private const val SETUP_ITEM_SPACING = 16
private const val CHOICE_LABEL_SPACING = 4
private const val RECEIVER_FALLBACK_ITEM_SPACING = 8
private const val SETTINGS_HEADER_VERTICAL_PADDING = 4
private const val HEADER_TEXT_WEIGHT = 1.0f
private const val FIRST_SLIDER_INDEX = 0
private const val MINIMUM_SLIDER_RANGE_INDEX = 1
private const val BITRATE_INPUT_WIDTH = 144
private const val PREVIEW_RECEIVER_ADDRESS = "192.168.1.20"
private const val SUMMARY_SEPARATOR = " · "

@Preview(name = "Stream setup ready")
@Composable
private fun StreamSetupReadyPreview() {
    dev.cambridge.sender.app.theme.CamBridgeTheme {
        StreamSetupScreen(
            state = previewState(
                receiverReadiness = ReceiverReadinessUiState.Ready(
                    receiverName = UiText.Plain("OBS Studio"),
                    address = UiText.Plain(PREVIEW_RECEIVER_ADDRESS),
                ),
            ),
            onAction = {},
        )
    }
}

@Preview(name = "Stream setup receiver unavailable")
@Composable
private fun StreamSetupReceiverUnavailablePreview() {
    dev.cambridge.sender.app.theme.CamBridgeTheme {
        StreamSetupScreen(
            state = previewState(
                receiverReadiness = ReceiverReadinessUiState.Unavailable(
                    message = UiText.Resource(R.string.receiver_not_found_support),
                ),
            ),
            onAction = {},
        )
    }
}

@Preview(name = "Stream setup unsupported mode")
@Composable
private fun StreamSetupUnsupportedModePreview() {
    dev.cambridge.sender.app.theme.CamBridgeTheme {
        StreamSetupScreen(
            state = previewState(
                receiverReadiness = ReceiverReadinessUiState.Ready(
                    receiverName = UiText.Plain("OBS Studio"),
                    address = UiText.Plain(PREVIEW_RECEIVER_ADDRESS),
                ),
                selectedProfileSupported = false,
                bitrate = BitrateUiState(),
                validationMessage = UiText.Resource(R.string.video_mode_unavailable),
            ),
            onAction = {},
        )
    }
}

private fun previewState(
    receiverReadiness: ReceiverReadinessUiState,
    selectedProfileSupported: Boolean = true,
    bitrate: BitrateUiState? = null,
    validationMessage: UiText? = null,
): StreamSetupUiState {
    val selectedProfile = VideoProfiles.PROFILE_1080P30
    return StreamSetupUiState(
        receiverReadiness = receiverReadiness,
        resolutionOptions = VideoProfiles.qualityProfiles.map { profile ->
            SelectOptionUi(
                key = profile.id,
                label = StreamPresentationMapper.videoProfileLabel(profile),
                isSelected = profile.width == selectedProfile.width && profile.height == selectedProfile.height,
                isEnabled = profile.id == selectedProfile.id,
            )
        },
        frameRateOptions = VideoProfiles.profilesForResolution(selectedProfile).map { profile ->
            SelectOptionUi(
                key = profile.fps.toString(),
                label = UiText.Resource(R.string.frame_rate_option, listOf(profile.fps)),
                isSelected = profile.id == selectedProfile.id,
            )
        },
        orientationOptions = listOf(
            SelectOptionUi(
                key = StreamOrientation.LANDSCAPE.name,
                label = UiText.Resource(R.string.landscape),
                isSelected = true,
            ),
        ),
        bitrate = bitrate ?: BitrateUiState(
            isAvailable = true,
            selectedBps = selectedProfile.defaultBitrateBps,
            minimumBps = selectedProfile.minimumBitrateBps,
            maximumBps = selectedProfile.maximumBitrateBps,
            stepBps = selectedProfile.bitrateStepBps,
        ),
        selectedProfile = selectedProfile,
        selectedOrientation = StreamOrientation.LANDSCAPE,
        selectedProfileSupported = selectedProfileSupported,
        validationMessage = validationMessage,
    )
}
