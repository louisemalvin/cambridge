package dev.cambridge.sender.app.startup

import dev.cambridge.sender.app.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupStateResolverTest {
    @Test
    fun configuredReceiverRestoresStreamSetupDestination() {
        assertEquals(
            AppDestination.StreamSetup,
            StartupStateResolver(hasConfiguredReceiver = true).resolveInitialDestination(),
        )
    }

    @Test
    fun activeStreamRestoresWebcamDestination() {
        assertEquals(
            AppDestination.Webcam,
            StartupStateResolver(
                hasConfiguredReceiver = true,
                hasActiveStream = true,
            ).resolveInitialDestination(),
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
