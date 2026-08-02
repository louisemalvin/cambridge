package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
            Switch(
                checked = state.isEnabled,
                onCheckedChange = onStabilizationEnabledChanged,
            )
        },
        modifier = modifier.fillMaxWidth(),
    )
}
