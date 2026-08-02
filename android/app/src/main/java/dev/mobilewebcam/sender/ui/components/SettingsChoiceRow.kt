package dev.mobilewebcam.sender.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.mobilewebcam.sender.ui.model.Content
import dev.mobilewebcam.sender.ui.model.SelectOptionUi
import dev.mobilewebcam.sender.ui.model.UiText

@Composable
fun SettingsChoiceRow(
    title: UiText,
    options: List<SelectOptionUi>,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMenuExpanded by remember { mutableStateOf(false) }
    val selectedOption = options.firstOrNull { it.isSelected }

    Box(modifier = modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = options.isNotEmpty()) {
                    isMenuExpanded = true
                },
            headlineContent = { title.Content() },
            supportingContent = { selectedOption?.label?.Content() },
            trailingContent = {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            },
        )
        DropdownMenu(
            expanded = isMenuExpanded,
            onDismissRequest = { isMenuExpanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { option.label.Content() },
                    onClick = {
                        isMenuExpanded = false
                        onSelected(option.key)
                    },
                )
            }
        }
    }
}
