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
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.model.VideoProfile

@Composable
fun VideoProfileSelector(
    selected: VideoProfile,
    onSelected: (VideoProfile) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Video profile")
        TextButton(onClick = { expanded = true }) {
            Text(selected.label())
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoProfiles.all.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.label()) },
                    onClick = {
                        expanded = false
                        onSelected(profile)
                    },
                )
            }
        }
    }
}

private fun VideoProfile.label(): String = when (id) {
    "1080p30" -> "1080p30 - 1920 x 1080"
    "1440p30" -> "1440p30 - 2560 x 1440"
    "4k30" -> "4K UHD30 - 3840 x 2160 (experimental)"
    else -> "$width x $height @ ${fps} FPS"
}
