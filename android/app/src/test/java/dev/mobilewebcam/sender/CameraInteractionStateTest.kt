package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.media.camera.CameraStabilizationMode
import dev.mobilewebcam.sender.media.camera.CameraStabilizationSupport
import dev.mobilewebcam.sender.media.camera.physicalLensOptionsFor
import dev.mobilewebcam.sender.media.camera.preferredStabilizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraInteractionStateTest {
    @Test
    fun cameraBoundsClampZoomAndMarkCameraActive() {
        val state = CameraInteractionState()
            .withCameraBounds(minimum = 1.0f, maximum = 4.0f, current = 2.0f)

        assertTrue(state.isCameraActive)
        assertTrue(state.isZoomSupported)
        assertEquals(1.0f, state.withZoomRatio(0.5f).zoomRatio, FLOAT_TOLERANCE)
        assertEquals(4.0f, state.withZoomRatio(8.0f).zoomRatio, FLOAT_TOLERANCE)
    }

    @Test
    fun resetReturnsToTheDefaultZoomRatio() {
        val state = CameraInteractionState()
            .withCameraBounds(minimum = 1.0f, maximum = 4.0f, current = 3.0f)

        assertEquals(1.0f, state.resetZoom().zoomRatio, FLOAT_TOLERANCE)
    }

    @Test
    fun inactiveStateHasNoCameraZoomRange() {
        val state = CameraInteractionState.inactive()

        assertFalse(state.isCameraActive)
        assertFalse(state.isZoomSupported)
        assertEquals(1.0f, state.zoomRatio, FLOAT_TOLERANCE)
    }

    @Test
    fun physicalLensOptionsUseRuntimeIdsAndDefaultToAutomatic() {
        val options = physicalLensOptionsFor(listOf("3", "2", "3"))
        val state = CameraInteractionState().withPhysicalLensOptions(options)

        assertEquals(listOf("Auto", "Lens 3", "Lens 2"), options.map { it.label })
        assertEquals(options.first(), state.selectedPhysicalLens)
        assertEquals(options[2], state.withSelectedPhysicalLens(options[2]).selectedPhysicalLens)
    }

    @Test
    fun noPhysicalCameraIdsProduceNoLensControls() {
        assertTrue(physicalLensOptionsFor(emptyList()).isEmpty())
    }

    @Test
    fun stabilizationPrefersOpticalBeforeElectronic() {
        val support = CameraStabilizationSupport(
            opticalSupported = true,
            electronicSupported = true,
        )

        assertEquals(CameraStabilizationMode.OPTICAL, preferredStabilizationMode(support))
    }

    @Test
    fun stabilizationFallsBackToElectronicWhenOpticalIsUnavailable() {
        val support = CameraStabilizationSupport(
            opticalSupported = false,
            electronicSupported = true,
        )

        assertEquals(CameraStabilizationMode.ELECTRONIC, preferredStabilizationMode(support))
    }

    @Test
    fun stabilizationStateTracksSupportAndEnabledState() {
        val state = CameraInteractionState()
            .withStabilizationSupport(supported = true)
            .withStabilizationEnabled(enabled = true)

        assertTrue(state.isStabilizationSupported)
        assertTrue(state.isStabilizationEnabled)
        assertFalse(
            state.withStabilizationSupport(supported = false).isStabilizationEnabled,
        )
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
