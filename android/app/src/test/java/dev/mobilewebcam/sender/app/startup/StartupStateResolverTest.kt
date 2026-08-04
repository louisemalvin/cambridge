package dev.mobilewebcam.sender.app.startup

import dev.mobilewebcam.sender.app.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupStateResolverTest {
    @Test
    fun configuredReceiverRestoresWebcamDestination() {
        assertEquals(
            AppDestination.Webcam,
            StartupStateResolver(hasConfiguredReceiver = true).resolveInitialDestination(),
        )
    }

    @Test
    fun missingReceiverStartsPairingDestination() {
        assertEquals(
            AppDestination.Pairing,
            StartupStateResolver(hasConfiguredReceiver = false).resolveInitialDestination(),
        )
    }
}
