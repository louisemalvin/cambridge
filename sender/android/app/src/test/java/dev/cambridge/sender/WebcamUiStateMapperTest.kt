package dev.cambridge.sender

import dev.cambridge.sender.R
import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.SenderDialogUiState
import dev.cambridge.sender.app.model.StreamPresentationSnapshot
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.feature.webcam.WebcamUiStateMapper
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.DisplayOrientation
import dev.cambridge.sender.media.camera.SessionTransform
import dev.cambridge.sender.model.OutputPixelFormat
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamSession
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebcamUiStateMapperTest {
    @Test
    fun idleMapsToWaitingPreview() {
        val state = mapSnapshot()

        assertEquals(ConnectionUiState.Waiting, state.connection)
        assertFalse(state.preview.isLive)
        assertEquals(16.0f / 9.0f, state.preview.landscapeAspectRatio, ASPECT_RATIO_TOLERANCE)
    }

    @Test
    fun cameraPermissionMapsToUiDialogWithoutExposingPermissionState() {
        val state = mapSnapshot(isPermissionDialogOpen = true)

        assertTrue(state.dialog is SenderDialogUiState.CameraPermission)
    }

    @Test
    fun streamingMapsToConnectionPresentationAndLivePreview() {
        val state = mapSnapshot(
            streamState = StreamState.Streaming(
                session = StreamSession(
                    sessionId = "session",
                    endpoint = ReceiverEndpoint("desktop", 50_000),
                    selectedCodec = VideoCodec.H264,
                    profile = VideoProfiles.default,
                    bitrateBps = VideoProfiles.default.defaultBitrateBps,
                    mediaPort = 50_001,
                    outputPixelFormat = OutputPixelFormat.NV12,
                    warnings = emptyList(),
                    sessionTransform = SessionTransform.forProfile(DisplayOrientation.PORTRAIT),
                ),
                startedAtMillis = 0,
            ),
            activeReceiverName = "Test desktop",
        )

        val connection = state.connection as ConnectionUiState.Streaming
        assertEquals(UiText.Plain("Test desktop"), connection.receiverName)
        assertEquals(UiText.Resource(R.string.portrait), state.sessionOrientation)
        assertTrue(state.preview.isLive)
    }

    @Test
    fun failureMapsToCopyableDiagnosticsWithoutDomainStateInScreenState() {
        val state = mapSnapshot(
            streamState = StreamState.Failed(StreamFailure.ReceiverUnavailable("connection failed")),
            activeReceiverName = "Test desktop",
        )

        assertTrue(state.connection is ConnectionUiState.Failed)
        assertEquals(UiText.Plain("OBS is not available"), (state.connection as ConnectionUiState.Failed).message)
        assertTrue(state.failureDiagnostics?.contains("Test desktop") == true)
    }

    @Test
    fun connectingMapsToSimpleConnectionStatus() {
        assertTrue(mapSnapshot(StreamState.Connecting).connection is ConnectionUiState.Connecting)
        assertFalse(mapSnapshot(StreamState.Connecting).preview.isLive)
    }

    @Test
    fun stoppingStateMapsToStoppingUiState() {
        val state = mapSnapshot(streamState = StreamState.Stopping)
        assertEquals(ConnectionUiState.Stopping, state.connection)
        assertTrue(state.preview.isLive)
    }

    private fun mapSnapshot(
        streamState: StreamState = StreamState.Idle,
        activeReceiverName: String? = null,
        isPermissionDialogOpen: Boolean = false,
    ) = WebcamUiStateMapper.map(
        snapshot = StreamPresentationSnapshot(
            profile = VideoProfiles.default,
            cameraInteraction = CameraInteractionState(),
            streamState = streamState,
            activeReceiverName = activeReceiverName,
            validationMessage = null,
        ),
        cameraPermissionGranted = true,
        isScreenDimmed = false,
        isZoomTrayOpen = false,
        isPermissionDialogOpen = isPermissionDialogOpen,
    )

    private companion object {
        const val ASPECT_RATIO_TOLERANCE = 0.0001f
    }
}
