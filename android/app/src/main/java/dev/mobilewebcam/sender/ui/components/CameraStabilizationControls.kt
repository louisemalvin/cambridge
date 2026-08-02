package dev.mobilewebcam.sender.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.StabilizationUiState

@Composable
fun CameraStabilizationControls(
    state: StabilizationUiState,
    onStabilizationEnabledChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isSupported) return

    ListItem(
        modifier = modifier.fillMaxWidth(),
        headlineContent = { Text(androidx.compose.ui.res.stringResource(R.string.stabilization)) },
        supportingContent = {
            Text(
                androidx.compose.ui.res.stringResource(
                    if (state.isEnabled) R.string.on else R.string.off,
                ),
            )
        },
        trailingContent = {
            Switch(
                checked = state.isEnabled,
                onCheckedChange = onStabilizationEnabledChanged,
            )
        },
    )
}
