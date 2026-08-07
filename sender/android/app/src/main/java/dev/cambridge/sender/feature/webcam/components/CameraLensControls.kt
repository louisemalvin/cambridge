package dev.cambridge.sender.feature.webcam.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.cambridge.sender.R
import dev.cambridge.sender.app.model.LensOptionUi
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.app.theme.CamBridgeTheme
import dev.cambridge.sender.app.model.value
import dev.cambridge.sender.feature.settings.components.SettingsChoiceRow

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

@Composable
fun FloatingLensSelector(
    options: List<LensOptionUi>,
    onLensSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.size <= 1) return

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = FLOATING_LENS_ALPHA),
        tonalElevation = FLOATING_LENS_TONAL_ELEVATION.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = FLOATING_LENS_HORIZONTAL_PADDING.dp, vertical = FLOATING_LENS_VERTICAL_PADDING.dp),
            horizontalArrangement = Arrangement.spacedBy(FLOATING_LENS_SPACING.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.isSelected,
                    onClick = { onLensSelected(option.key) },
                    label = { Text(option.label.value()) },
                )
            }
        }
    }
}

@Preview(name = "Floating Lens Selector")
@Composable
private fun FloatingLensSelectorPreview() {
    CamBridgeTheme {
        FloatingLensSelector(
            options = listOf(
                LensOptionUi("0", UiText.Plain("0.5x"), false),
                LensOptionUi("1", UiText.Plain("1x"), true),
                LensOptionUi("2", UiText.Plain("2x"), false),
            ),
            onLensSelected = {},
        )
    }
}

private const val FLOATING_LENS_ALPHA = 0.92f
private const val FLOATING_LENS_TONAL_ELEVATION = 3
private const val FLOATING_LENS_HORIZONTAL_PADDING = 8
private const val FLOATING_LENS_VERTICAL_PADDING = 4
private const val FLOATING_LENS_SPACING = 6
