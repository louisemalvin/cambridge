package dev.mobilewebcam.sender.app.startup

import dev.mobilewebcam.sender.app.navigation.AppDestination
import dev.mobilewebcam.sender.connection.discovery.PairingStore

class StartupStateResolver(
    private val pairings: PairingStore,
) {
    fun resolveInitialDestination(): AppDestination {
        return if (pairings.hasApprovedReceivers()) {
            AppDestination.Webcam
        } else {
            AppDestination.Pairing
        }
    }
}
