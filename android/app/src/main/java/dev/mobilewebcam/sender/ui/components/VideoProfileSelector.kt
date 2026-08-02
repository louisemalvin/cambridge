package dev.mobilewebcam.sender.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.SelectOptionUi
import dev.mobilewebcam.sender.ui.model.UiText

@Composable
fun VideoProfileSelector(
    options: List<SelectOptionUi>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsChoiceRow(
        title = UiText.Resource(R.string.video_profile),
        options = options,
        onSelected = onSelected,
        modifier = modifier,
    )
}
