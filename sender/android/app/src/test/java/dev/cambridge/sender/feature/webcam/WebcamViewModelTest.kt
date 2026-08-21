package dev.cambridge.sender.feature.webcam

import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.StreamPresentationSnapshot
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraLensFacing
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebcamViewModelTest {
    @Test
    fun waitingStateIsNotLive() {
        val state = WebcamUiStateMapper.map(
            snapshot = snapshot(),
            isScreenDimmed = false,
            isZoomTrayOpen = false,
        )

        assertEquals(ConnectionUiState.Waiting, state.connection)
        assertFalse(state.preview.isLive)
        assertFalse(state.isScreenDimmed)
        assertFalse(state.isZoomTrayOpen)
    }

    @Test
    fun temporaryDisconnectionRemainsOnWebcamAsFailureState() {
        val state = WebcamUiStateMapper.map(
            snapshot = snapshot(streamState = StreamState.Failed(StreamFailure.NetworkDisconnected)),
            isScreenDimmed = false,
            isZoomTrayOpen = false,
        )

        assertTrue(state.connection is ConnectionUiState.Failed)
        assertTrue(state.failureDiagnostics != null)
    }

    @Test
    fun cameraControlsAreMappedWithoutExposingCameraFrameworkTypes() {
        val state = WebcamUiStateMapper.map(
            snapshot = snapshot(
                cameraInteraction = CameraInteractionState()
                    .withCameraBounds(minimum = 1.0f, maximum = 4.0f, current = 2.0f),
            ),
            isScreenDimmed = false,
            isZoomTrayOpen = false,
        )

        assertEquals(2.0f, state.camera.zoom.ratio, FLOAT_TOLERANCE)
        assertEquals(4.0f, state.camera.zoom.maximumRatio, FLOAT_TOLERANCE)
        assertTrue(state.camera.zoom.isCameraActive)
    }

    @Test
    fun cameraFacingMapsToOneFlipControl() {
        val state = WebcamUiStateMapper.map(
            snapshot = snapshot(
                cameraInteraction = CameraInteractionState().withCameraSelection(
                    facing = CameraLensFacing.FRONT,
                    availableFacings = listOf(CameraLensFacing.BACK, CameraLensFacing.FRONT),
                    lensOptions = emptyList(),
                    selectedLens = null,
                ),
            ),
            isScreenDimmed = false,
            isZoomTrayOpen = false,
        )

        assertTrue(state.camera.isFrontCamera)
        assertTrue(state.camera.canFlipCamera)
        assertTrue(state.camera.lensOptions.isEmpty())
    }

    private fun snapshot(
        streamState: StreamState = StreamState.Idle,
        cameraInteraction: CameraInteractionState = CameraInteractionState(),
    ) = StreamPresentationSnapshot(
        profile = VideoProfiles.default,
        cameraInteraction = cameraInteraction,
        streamState = streamState,
        activeReceiverName = null,
        validationMessage = null,
    )

    private companion object {
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
