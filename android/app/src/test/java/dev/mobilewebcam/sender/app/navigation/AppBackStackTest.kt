package dev.mobilewebcam.sender.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackStackTest {
    @Test
    fun initialDestinationIsSet() {
        val backStack = AppBackStack(AppDestination.Pairing)
        assertEquals(AppDestination.Pairing, backStack.current)
    }

    @Test
    fun navigateToPushesNewDestination() {
        val backStack = AppBackStack(AppDestination.Pairing)
        backStack.navigateTo(AppDestination.Webcam)
        assertEquals(AppDestination.Webcam, backStack.current)
        assertEquals(2, backStack.elements.size)
    }

    @Test
    fun popRemovesTopDestination() {
        val backStack = AppBackStack(AppDestination.Webcam)
        backStack.navigateTo(AppDestination.Settings)
        assertTrue(backStack.pop())
        assertEquals(AppDestination.Webcam, backStack.current)
    }

    @Test
    fun popOnSingleItemReturnsFalse() {
        val backStack = AppBackStack(AppDestination.Pairing)
        assertFalse(backStack.pop())
        assertEquals(AppDestination.Pairing, backStack.current)
    }

    @Test
    fun popToPairingResetsToPairing() {
        val backStack = AppBackStack(AppDestination.Webcam)
        backStack.navigateTo(AppDestination.Settings)
        backStack.popToPairing()
        assertEquals(AppDestination.Pairing, backStack.current)
        assertEquals(1, backStack.elements.size)
    }

    @Test
    fun replaceWithWebcamResetsToWebcam() {
        val backStack = AppBackStack(AppDestination.Pairing)
        backStack.replaceWithWebcam()
        assertEquals(AppDestination.Webcam, backStack.current)
        assertEquals(1, backStack.elements.size)
    }
}
