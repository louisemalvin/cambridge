package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.feature.webcam.WebcamUiStateMapper
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.session.VideoProfiles
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
                session = NegotiatedSession(
                    sessionId = "session",
                    endpoint = ReceiverEndpoint("desktop", 50000),
                    selectedCodec = VideoCodec.H264,
                    profile = VideoProfiles.default,
                    bitrateBps = VideoProfiles.default.h264BitrateBps,
                    mediaPort = 50001,
                    outputPixelFormat = OutputPixelFormat.NV12,
                    warnings = emptyList(),
                ),
                startedAtMillis = 0,
            ),
            activeReceiverName = "Test desktop",
        )

        val connection = state.connection as ConnectionUiState.Streaming
        assertEquals(UiText.Plain("Test desktop"), connection.receiverName)
        assertTrue(state.preview.isLive)
    }

    @Test
    fun failureMapsToCopyableDiagnosticsWithoutDomainStateInScreenState() {
        val state = mapSnapshot(
            streamState = StreamState.Failed(
                StreamFailure.ReceiverUnavailable("Health check failed"),
            ),
            activeReceiverName = "Test desktop",
        )

        assertTrue(state.connection is ConnectionUiState.Failed)
        assertTrue(state.failureDiagnostics?.contains("Test desktop") == true)
    }

    @Test
    fun connectingStateMapsToConnectingUiState() {
        val state = mapSnapshot(streamState = StreamState.Negotiating)
        assertTrue(state.connection is ConnectionUiState.Connecting)
        assertFalse(state.preview.isLive)
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
            codecPreference = CodecPreference.AUTO_PREFER_H265,
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
