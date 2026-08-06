package dev.mobilewebcam.sender.app.startup

import dev.mobilewebcam.sender.app.navigation.AppDestination

class StartupStateResolver(
    private val hasConfiguredReceiver: Boolean = false,
    private val hasActiveStream: Boolean = false,
) {
    fun resolveInitialDestination(): AppDestination {
        return when {
            hasActiveStream -> AppDestination.Webcam
            hasConfiguredReceiver -> AppDestination.StreamSetup
            else -> AppDestination.Pairing
        }
    }
}
