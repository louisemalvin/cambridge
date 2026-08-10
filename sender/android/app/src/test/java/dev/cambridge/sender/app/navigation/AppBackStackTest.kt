package dev.cambridge.sender.app.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppBackStackTest {
    @Test
    fun initialDestinationIsSet() {
        val backStack = AppBackStack(AppDestination.StreamSetup)
        assertEquals(AppDestination.StreamSetup, backStack.current)
    }

    @Test
    fun navigateToPushesNewDestination() {
        val backStack = AppBackStack(AppDestination.StreamSetup)
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
        val backStack = AppBackStack(AppDestination.StreamSetup)
        assertFalse(backStack.pop())
        assertEquals(AppDestination.StreamSetup, backStack.current)
    }

    @Test
    fun replaceWithWebcamResetsToWebcam() {
        val backStack = AppBackStack(AppDestination.StreamSetup)
        backStack.replaceWithWebcam()
        assertEquals(AppDestination.Webcam, backStack.current)
        assertEquals(1, backStack.elements.size)
    }

    @Test
    fun replaceWithStreamSetupResetsToSetup() {
        val backStack = AppBackStack(AppDestination.Webcam)
        backStack.replaceWithStreamSetup()
        assertEquals(AppDestination.StreamSetup, backStack.current)
        assertEquals(1, backStack.elements.size)
    }
}
