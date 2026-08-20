package dev.cambridge.sender.feature.permission

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cambridge.sender.R
import dev.cambridge.sender.app.permission.CameraPermissionSnapshot

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CameraPermissionScreen(
    permission: CameraPermissionSnapshot,
    onPrimaryAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val messageResource = if (permission.isPermanentlyDenied) {
        R.string.camera_permission_blocked_message
    } else {
        R.string.camera_permission_message
    }
    val actionResource = when {
        permission.isPermanentlyDenied -> R.string.camera_permission_open_settings
        permission.hasBeenDenied -> R.string.camera_permission_try_again
        else -> R.string.camera_permission_request
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(PERMISSION_SCREEN_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(PERMISSION_ITEM_SPACING.dp),
        ) {
            Text(
                text = stringResource(R.string.camera_permission_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(messageResource),
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(
                onClick = onPrimaryAction,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(actionResource))
            }
        }
    }
}

private const val PERMISSION_SCREEN_PADDING = 24
private const val PERMISSION_ITEM_SPACING = 16
