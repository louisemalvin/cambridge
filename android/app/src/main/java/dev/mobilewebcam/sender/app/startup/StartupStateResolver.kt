package dev.mobilewebcam.sender.app.startup

import dev.mobilewebcam.sender.app.navigation.AppDestination

class StartupStateResolver(
    private val hasApprovedReceivers: Boolean,
) {
    fun resolveInitialDestination(): AppDestination {
        return if (hasApprovedReceivers) {
            AppDestination.Webcam
        } else {
            AppDestination.Pairing
        }
    }
}
