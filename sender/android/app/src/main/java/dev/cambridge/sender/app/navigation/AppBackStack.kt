package dev.cambridge.sender.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList

class AppBackStack(
    initialDestination: AppDestination,
    val elements: SnapshotStateList<AppDestination> = mutableStateListOf(initialDestination),
) {
    val current: AppDestination
        get() = elements.lastOrNull() ?: AppDestination.CameraPermission

    fun navigateTo(destination: AppDestination) {
        if (current != destination) {
            elements.add(destination)
        }
    }

    fun pop(): Boolean {
        if (elements.size > 1) {
            elements.removeAt(elements.lastIndex)
            return true
        }
        return false
    }

    fun replaceWithWebcam() {
        elements.clear()
        elements.add(AppDestination.Webcam)
    }

    fun replaceWithCameraPermission() {
        elements.clear()
        elements.add(AppDestination.CameraPermission)
    }

    fun replaceWithStreamSetup() {
        elements.clear()
        elements.add(AppDestination.StreamSetup)
    }
}

@Composable
fun rememberAppBackStack(initialDestination: AppDestination): AppBackStack {
    return remember {
        AppBackStack(initialDestination)
    }
}
