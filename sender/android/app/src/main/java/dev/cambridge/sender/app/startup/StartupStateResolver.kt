package dev.cambridge.sender.app.startup

import dev.cambridge.sender.app.navigation.AppDestination

class StartupStateResolver(
    private val hasCameraPermission: Boolean = true,
    private val hasActiveStream: Boolean = false,
) {
    fun resolveInitialDestination(): AppDestination {
        return when {
            !hasCameraPermission -> AppDestination.CameraPermission
            hasActiveStream -> AppDestination.Webcam
            else -> AppDestination.StreamSetup
        }
    }
}
