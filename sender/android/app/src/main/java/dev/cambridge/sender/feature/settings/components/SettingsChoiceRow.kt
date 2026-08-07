package dev.cambridge.sender.feature.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.value

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsChoiceRow(
    titleResourceId: Int,
    options: List<SelectOptionUi>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = ROW_VERTICAL_PADDING.dp),
        verticalArrangement = Arrangement.spacedBy(ROW_ITEM_SPACING.dp),
    ) {
        Text(
            text = stringResource(titleResourceId),
            style = MaterialTheme.typography.titleMedium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CHIP_HORIZONTAL_SPACING.dp),
            verticalArrangement = Arrangement.spacedBy(CHIP_VERTICAL_SPACING.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option.isSelected,
                    onClick = { onSelected(option.key) },
                    enabled = option.isEnabled,
                    label = { Text(option.label.value()) },
                )
            }
        }
    }
}

private const val ROW_VERTICAL_PADDING = 4
private const val ROW_ITEM_SPACING = 4
private const val CHIP_HORIZONTAL_SPACING = 8
private const val CHIP_VERTICAL_SPACING = 4
