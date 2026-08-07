package dev.cambridge.sender.feature.webcam.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.cambridge.sender.R
import dev.cambridge.sender.app.model.AntiFlickerUiState
import dev.cambridge.sender.feature.settings.components.SettingsChoiceRow
import dev.cambridge.sender.media.camera.AntiFlickerMode

@Composable
fun CameraAntiFlickerControls(
    state: AntiFlickerUiState,
    onModeSelected: (AntiFlickerMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.options.isEmpty()) return

    SettingsChoiceRow(
        titleResourceId = R.string.anti_flicker,
        options = state.options,
        onSelected = { key ->
            runCatching { AntiFlickerMode.valueOf(key) }
                .getOrNull()
                ?.let(onModeSelected)
        },
        modifier = modifier,
    )
}
