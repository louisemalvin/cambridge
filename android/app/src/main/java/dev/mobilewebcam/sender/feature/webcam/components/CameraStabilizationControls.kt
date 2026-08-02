package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.StabilizationUiState

@Composable
fun CameraStabilizationControls(
    state: StabilizationUiState,
    onStabilizationEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isSupported) return

    ListItem(
        headlineContent = { Text(stringResource(R.string.stabilization)) },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(STABILIZATION_STATUS_SPACING.dp)) {
                Text(
                    text = stringResource(
                        if (state.isEnabled) R.string.on else R.string.off,
                    ),
                )
                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = onStabilizationEnabledChanged,
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    )
}

private const val STABILIZATION_STATUS_SPACING = 8
