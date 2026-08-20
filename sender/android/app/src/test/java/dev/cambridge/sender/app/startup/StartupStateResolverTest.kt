package dev.cambridge.sender.app.startup

import dev.cambridge.sender.app.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupStateResolverTest {
    @Test
    fun missingPermissionAlwaysStartsOnThePermissionRoute() {
        assertEquals(
            AppDestination.CameraPermission,
            StartupStateResolver(hasCameraPermission = false, hasActiveStream = true)
                .resolveInitialDestination(),
        )
    }

    @Test
    fun noActiveStreamRestoresStreamSetupDestination() {
        assertEquals(
            AppDestination.StreamSetup,
            StartupStateResolver().resolveInitialDestination(),
        )
    }

    @Test
    fun activeStreamRestoresWebcamDestination() {
        assertEquals(
            AppDestination.Webcam,
            StartupStateResolver(
                hasActiveStream = true,
            ).resolveInitialDestination(),
        )
    }

}
