package dev.cambridge.sender

import dev.cambridge.sender.media.camera.AppliedVideoStabilizationMode
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraStabilizationApplyStatus
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.media.camera.CameraStabilizationObservation
import dev.cambridge.sender.media.camera.CameraStabilizationReducer
import dev.cambridge.sender.media.camera.RequestedVideoStabilizationMode
import dev.cambridge.sender.media.camera.PhysicalLensOption
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

        assertEquals(options.first(), state.selectedPhysicalLens)
        assertEquals(options[2], state.withSelectedPhysicalLens(options[2]).selectedPhysicalLens)
    }

    @Test
    fun explicitModesConfirmOnlyFromMatchingCaptureResults() {
        val supported = CameraInteractionState().withStabilizationState(
            CameraStabilizationReducer.withSupportedModes(
                current = CameraInteractionState().stabilization,
                supportedModes = listOf(CameraStabilizationMode.OPTICAL, CameraStabilizationMode.ELECTRONIC),
                persistedPreference = CameraStabilizationMode.OPTICAL,
            ),
        )
        val applying = supported.withStabilizationState(
            CameraStabilizationReducer.request(
                current = supported.stabilization,
                requestedMode = CameraStabilizationMode.OPTICAL,
                nowMillis = 100L,
            ),
        )
        val stillApplying = CameraStabilizationReducer.observe(
            applying.stabilization,
            CameraStabilizationObservation(AppliedVideoStabilizationMode.OFF, opticalOn = false),
            200L,
        )
        assertEquals(CameraStabilizationApplyStatus.APPLYING, stillApplying.applyStatus)
        val applied = CameraStabilizationReducer.observe(
            stillApplying,
            CameraStabilizationObservation(AppliedVideoStabilizationMode.OFF, opticalOn = true),
            240L,
        )
        assertEquals(CameraStabilizationApplyStatus.APPLIED, applied.applyStatus)
        assertEquals(CameraStabilizationMode.OPTICAL, applied.appliedMode)
    }

    @Test
    fun stabilizationRequestsAreMutuallyExclusive() {
        assertEquals(
            dev.cambridge.sender.media.camera.CameraStabilizationRequest(
                videoMode = RequestedVideoStabilizationMode.OFF,
                opticalOn = false,
            ),
            CameraStabilizationReducer.requestFor(CameraStabilizationMode.OFF),
        )
        assertEquals(
            dev.cambridge.sender.media.camera.CameraStabilizationRequest(
                videoMode = RequestedVideoStabilizationMode.OFF,
                opticalOn = true,
            ),
            CameraStabilizationReducer.requestFor(CameraStabilizationMode.OPTICAL),
        )
        assertEquals(
            dev.cambridge.sender.media.camera.CameraStabilizationRequest(
                videoMode = RequestedVideoStabilizationMode.ELECTRONIC,
                opticalOn = false,
            ),
            CameraStabilizationReducer.requestFor(CameraStabilizationMode.ELECTRONIC),
        )
        assertEquals(
            dev.cambridge.sender.media.camera.CameraStabilizationRequest(
                videoMode = RequestedVideoStabilizationMode.PREVIEW,
                opticalOn = false,
            ),
            CameraStabilizationReducer.requestFor(CameraStabilizationMode.PREVIEW),
        )
    }

    @Test
    fun unsupportedModeExpiresAsUnavailableAndAppliedModeRemainsOff() {
        val current = CameraStabilizationReducer.withSupportedModes(
            current = CameraInteractionState().stabilization,
            supportedModes = listOf(CameraStabilizationMode.ELECTRONIC),
            persistedPreference = CameraStabilizationMode.ELECTRONIC,
        )
        val applying = CameraStabilizationReducer.request(current, CameraStabilizationMode.ELECTRONIC, 10L)
        val unavailable = CameraStabilizationReducer.observe(
            applying,
            CameraStabilizationObservation(AppliedVideoStabilizationMode.OFF, opticalOn = false),
            10L + CameraStabilizationReducer.CONFIRMATION_DEADLINE_MILLIS,
        )
        assertEquals(CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM, unavailable.applyStatus)
        assertEquals(CameraStabilizationMode.OFF, unavailable.appliedMode)
    }

    @Test
    fun unsupportedPersistedPreferenceStaysUnavailableWhenCameraReportsOff() {
        val unavailable = CameraStabilizationReducer.withSupportedModes(
            current = CameraInteractionState().stabilization,
            supportedModes = emptyList(),
            persistedPreference = CameraStabilizationMode.ELECTRONIC,
        )

        val observed = CameraStabilizationReducer.observe(
            current = unavailable,
            observation = CameraStabilizationObservation(AppliedVideoStabilizationMode.OFF, opticalOn = false),
            nowMillis = 100L,
        )

        assertEquals(CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM, observed.applyStatus)
        assertEquals(CameraStabilizationMode.ELECTRONIC, observed.requestedMode)
        assertEquals(CameraStabilizationMode.OFF, observed.appliedMode)
    }

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
