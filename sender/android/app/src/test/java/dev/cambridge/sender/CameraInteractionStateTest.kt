package dev.cambridge.sender

import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.media.camera.CameraStabilizationSupport
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.media.camera.PhysicalLensOption
import dev.cambridge.sender.media.camera.preferredStabilizationMode
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
    fun physicalLensOptionsKeepRuntimeLabelsAndDefaultToAutomatic() {
        val options = listOf(
            PhysicalLensOption("Auto (Rear ID 0)", null),
            PhysicalLensOption("Physical (ID 2)", "2"),
            PhysicalLensOption("Physical (ID 3)", "3"),
        )
        val state = CameraInteractionState().withPhysicalLensOptions(options)

        assertEquals(
            listOf("Auto (Rear ID 0)", "Physical (ID 2)", "Physical (ID 3)"),
            options.map { it.label },
        )
        assertEquals(options.first(), state.selectedPhysicalLens)
        assertEquals(options[2], state.withSelectedPhysicalLens(options[2]).selectedPhysicalLens)
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

    @Test
    fun antiFlickerDefaultsToAutoAndTracksSelectedSupport() {
        val supportedModes = listOf(
            AntiFlickerMode.AUTO,
            AntiFlickerMode.HZ_50,
            AntiFlickerMode.HZ_60,
        )
        val state = CameraInteractionState().withAntiFlickerSupport(supportedModes)

        assertEquals(AntiFlickerMode.AUTO, state.antiFlickerMode)
        assertEquals(
            AntiFlickerMode.HZ_60,
            state.withAntiFlickerMode(AntiFlickerMode.HZ_60).antiFlickerMode,
        )
    }

    @Test
    fun antiFlickerFallsBackWhenAutoIsUnavailable() {
        val state = CameraInteractionState().withAntiFlickerSupport(
            listOf(AntiFlickerMode.HZ_50, AntiFlickerMode.HZ_60),
        )

        assertEquals(AntiFlickerMode.HZ_50, state.antiFlickerMode)
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
