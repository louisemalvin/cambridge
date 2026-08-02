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
import dev.mobilewebcam.sender.camera.CameraInteractionState

@Composable
fun CameraStabilizationControls(
    state: CameraInteractionState,
    onStabilizationEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isStabilizationSupported) return

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(STABILIZATION_CONTENT_PADDING_DP.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(STABILIZATION_LABEL_SPACING_DP.dp)) {
                Text(STABILIZATION_TITLE)
                Text(if (state.isStabilizationEnabled) STABILIZATION_ON_LABEL else STABILIZATION_OFF_LABEL)
            }
            Switch(
                checked = state.isStabilizationEnabled,
                onCheckedChange = onStabilizationEnabledChanged,
            )
        }
    }
}

private const val STABILIZATION_CONTENT_PADDING_DP = 12
private const val STABILIZATION_LABEL_SPACING_DP = 4
private const val STABILIZATION_TITLE = "Stabilization"
private const val STABILIZATION_ON_LABEL = "On"
private const val STABILIZATION_OFF_LABEL = "Off"
