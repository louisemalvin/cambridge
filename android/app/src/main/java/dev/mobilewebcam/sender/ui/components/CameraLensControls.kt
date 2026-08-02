package dev.mobilewebcam.sender.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.ui.model.Content
import dev.mobilewebcam.sender.ui.model.LensOptionUi

@Composable
fun CameraLensControls(
    options: List<LensOptionUi>,
    onLensSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PHYSICAL_LENS_CONTENT_PADDING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(PHYSICAL_LENS_TITLE_SPACING_DP.dp),
        ) {
            Text(androidx.compose.ui.res.stringResource(R.string.physical_lens))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(PHYSICAL_LENS_ITEM_SPACING_DP.dp),
            ) {
                options.forEach { lens ->
                    val isSelected = lens.isSelected
                    if (isSelected) {
                        Button(onClick = { onLensSelected(lens.key) }) {
                            lens.label.Content()
                        }
                    } else {
                        OutlinedButton(onClick = { onLensSelected(lens.key) }) {
                            lens.label.Content()
                        }
                    }
                }
            }
        }
    }
}

private const val PHYSICAL_LENS_CONTENT_PADDING_DP = 12
private const val PHYSICAL_LENS_ITEM_SPACING_DP = 8
private const val PHYSICAL_LENS_TITLE_SPACING_DP = 4
