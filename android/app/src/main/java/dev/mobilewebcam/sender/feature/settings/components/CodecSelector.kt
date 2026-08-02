package dev.mobilewebcam.sender.feature.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.SelectOptionUi

@Composable
fun CodecSelector(
    options: List<SelectOptionUi>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsChoiceRow(
        titleResourceId = R.string.codec_mode,
        options = options,
        onSelected = onSelected,
        modifier = modifier,
    )
}
