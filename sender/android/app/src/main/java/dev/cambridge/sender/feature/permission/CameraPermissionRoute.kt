package dev.cambridge.sender.feature.permission

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import dev.cambridge.sender.app.permission.CameraPermissionSnapshot

@Composable
fun CameraPermissionRoute(
    permission: CameraPermissionSnapshot,
    onPermissionRequestStarted: () -> Unit,
    onPermissionResult: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        onPermissionResult(granted)
    }

    CameraPermissionScreen(
        permission = permission,
        onPrimaryAction = {
            if (permission.isPermanentlyDenied) {
                onOpenSettings()
            } else {
                onPermissionRequestStarted()
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
        },
    )
}
