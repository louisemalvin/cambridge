package dev.mobilewebcam.sender.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.Content
import dev.mobilewebcam.sender.ui.model.SelectOptionUi

@Composable
fun CodecSelector(
    options: List<SelectOptionUi>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.isSelected }
    Column {
        Text(androidx.compose.ui.res.stringResource(R.string.codec_mode))
        TextButton(onClick = { expanded = true }, enabled = options.isNotEmpty()) {
            selected?.label?.Content()
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { option.label.Content() },
                    onClick = {
                        expanded = false
                        onSelected(option.key)
                    },
                )
            }
        }
    }
}
