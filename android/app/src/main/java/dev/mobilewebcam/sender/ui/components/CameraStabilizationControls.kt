package dev.mobilewebcam.sender.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.StabilizationUiState

@Composable
fun CameraStabilizationControls(
    state: StabilizationUiState,
    onStabilizationEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isSupported) return

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(STABILIZATION_CONTENT_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(STABILIZATION_LABEL_SPACING_DP.dp)) {
                Text(androidx.compose.ui.res.stringResource(R.string.stabilization))
                Text(
                    androidx.compose.ui.res.stringResource(
                        if (state.isEnabled) R.string.on else R.string.off,
                    ),
                )
            }
            Switch(
                checked = state.isEnabled,
                onCheckedChange = onStabilizationEnabledChanged,
            )
        }
    }
}

private const val STABILIZATION_CONTENT_PADDING_DP = 12
private const val STABILIZATION_LABEL_SPACING_DP = 4
