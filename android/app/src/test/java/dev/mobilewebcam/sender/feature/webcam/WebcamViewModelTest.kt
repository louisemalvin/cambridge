package dev.mobilewebcam.sender.feature.webcam

import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebcamViewModelTest {
    @Test
    fun waitingStateIsNotLive() {
        val state = WebcamUiStateMapper.map(
            snapshot = snapshot(),
            cameraPermissionGranted = true,
            isScreenDimmed = false,
            isZoomTrayOpen = false,
            isPermissionDialogOpen = false,
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
            cameraPermissionGranted = true,
            isScreenDimmed = false,
            isZoomTrayOpen = false,
            isPermissionDialogOpen = false,
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
            cameraPermissionGranted = true,
            isScreenDimmed = false,
            isZoomTrayOpen = false,
            isPermissionDialogOpen = false,
        )

        assertEquals(2.0f, state.camera.zoom.ratio, FLOAT_TOLERANCE)
        assertEquals(4.0f, state.camera.zoom.maximumRatio, FLOAT_TOLERANCE)
        assertTrue(state.camera.zoom.isCameraActive)
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
