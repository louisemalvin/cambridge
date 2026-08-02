package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.feature.settings.components.SettingsChoiceRow
import dev.mobilewebcam.sender.ui.model.LensOptionUi
import dev.mobilewebcam.sender.ui.model.SelectOptionUi

@Composable
fun CameraLensControls(
    options: List<LensOptionUi>,
    onLensSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return

    val selectableOptions = options.map { lens ->
        SelectOptionUi(
            key = lens.key,
            label = lens.label,
            isSelected = lens.isSelected,
        )
    }

    SettingsChoiceRow(
        titleResourceId = R.string.physical_lens,
        options = selectableOptions,
        onSelected = onLensSelected,
        modifier = modifier,
    )
}
