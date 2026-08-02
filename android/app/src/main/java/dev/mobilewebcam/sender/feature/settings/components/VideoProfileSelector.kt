package dev.mobilewebcam.sender.feature.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SelectOptionUi

@Composable
fun VideoProfileSelector(
    options: List<SelectOptionUi>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsChoiceRow(
        titleResourceId = R.string.video_profile,
        options = options,
        onSelected = onSelected,
        modifier = modifier,
    )
}
