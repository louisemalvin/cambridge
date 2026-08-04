package dev.mobilewebcam.sender.app.startup

import dev.mobilewebcam.sender.app.navigation.AppDestination

class StartupStateResolver(
    private val hasConfiguredReceiver: Boolean = false,
) {
    fun resolveInitialDestination(): AppDestination {
        return if (hasConfiguredReceiver) {
            AppDestination.Webcam
        } else {
            AppDestination.Pairing
        }
    }
}
