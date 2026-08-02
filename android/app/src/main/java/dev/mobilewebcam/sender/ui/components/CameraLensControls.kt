package dev.mobilewebcam.sender.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.LensOptionUi
import dev.mobilewebcam.sender.ui.model.SelectOptionUi
import dev.mobilewebcam.sender.ui.model.UiText

@Composable
fun CameraLensControls(
    options: List<LensOptionUi>,
    onLensSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return

    SettingsChoiceRow(
        title = UiText.Resource(R.string.physical_lens),
        options = options.map { lens ->
            SelectOptionUi(
                key = lens.key,
                label = lens.label,
                isSelected = lens.isSelected,
            )
        },
        onSelected = onLensSelected,
        modifier = modifier,
    )
}
