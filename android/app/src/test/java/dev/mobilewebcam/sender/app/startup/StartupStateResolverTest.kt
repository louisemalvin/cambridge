package dev.mobilewebcam.sender.app.startup

import dev.mobilewebcam.sender.app.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupStateResolverTest {
    @Test
    fun approvedReceiverRestoresWebcamDestination() {
        assertEquals(
            AppDestination.Webcam,
            StartupStateResolver(hasApprovedReceivers = true).resolveInitialDestination(),
        )
    }

    @Test
    fun missingApprovedReceiverStartsPairingDestination() {
        assertEquals(
            AppDestination.Pairing,
            StartupStateResolver(hasApprovedReceivers = false).resolveInitialDestination(),
        )
    }
}
