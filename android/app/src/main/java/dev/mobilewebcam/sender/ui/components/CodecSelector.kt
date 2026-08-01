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
import dev.mobilewebcam.sender.model.CodecPreference

@Composable
fun CodecSelector(
    selected: CodecPreference,
    onSelected: (CodecPreference) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Codec mode")
        TextButton(onClick = { expanded = true }) {
            Text(selected.label())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CodecPreference.entries.forEach { preference ->
                DropdownMenuItem(
                    text = { Text(preference.label()) },
                    onClick = {
                        expanded = false
                        onSelected(preference)
                    },
                )
            }
        }
    }
}

private fun CodecPreference.label(): String = when (this) {
    CodecPreference.AUTO_PREFER_H265 -> "Auto - prefer H.265"
    CodecPreference.FORCE_H264 -> "H.264"
    CodecPreference.FORCE_H265 -> "H.265"
}
