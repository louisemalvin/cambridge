package dev.cambridge.sender.feature.webcam.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cambridge.sender.R
import dev.cambridge.sender.app.model.StabilizationUiState
import dev.cambridge.sender.media.camera.CameraStabilizationApplyStatus
import dev.cambridge.sender.media.camera.CameraStabilizationMode

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CameraStabilizationControls(
    state: StabilizationUiState,
    onStabilizationModeChanged: (CameraStabilizationMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (state.supportedModes.isEmpty() ||
        (state.supportedModes.size == ONLY_OFF_MODE_COUNT &&
            state.supportedModes.single() == CameraStabilizationMode.OFF &&
            state.applyStatus != CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM)
    ) {
        return
    }
    val modes = state.supportedModes
    val status = stabilizationStatus(state)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(STATUS_SPACING.dp),
    ) {
        Text(
            text = stringResource(R.string.stabilization),
            style = MaterialTheme.typography.titleMedium,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.selectedMode == mode,
                    onClick = { onStabilizationModeChanged(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                    label = { Text(stabilizationLabel(mode)) },
                )
            }
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun stabilizationStatus(state: StabilizationUiState): String = when (state.applyStatus) {
    CameraStabilizationApplyStatus.IDLE ->
        stringResource(R.string.stabilization_selected, stabilizationLabel(state.selectedMode))
    CameraStabilizationApplyStatus.APPLYING ->
        stringResource(R.string.stabilization_applying, stabilizationLabel(state.requestedMode))
    CameraStabilizationApplyStatus.APPLIED ->
        stringResource(R.string.stabilization_applied, stabilizationLabel(state.appliedMode))
    CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM ->
        stringResource(R.string.stabilization_unavailable, stabilizationLabel(state.requestedMode))
}

@Composable
private fun stabilizationLabel(mode: CameraStabilizationMode): String = when (mode) {
    CameraStabilizationMode.OFF -> stringResource(R.string.off)
    CameraStabilizationMode.OPTICAL -> stringResource(R.string.stabilization_optical)
    CameraStabilizationMode.ELECTRONIC -> stringResource(R.string.stabilization_electronic)
    CameraStabilizationMode.PREVIEW -> stringResource(R.string.stabilization_preview)
}

private const val ONLY_OFF_MODE_COUNT = 1
private const val STATUS_SPACING = 4
