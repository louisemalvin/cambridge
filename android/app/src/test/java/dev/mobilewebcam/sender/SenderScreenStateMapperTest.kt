package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.SenderDialogUiState
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.feature.webcam.SenderDomainSnapshot
import dev.mobilewebcam.sender.feature.webcam.SenderScreenStateMapper
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderScreenStateMapperTest {
    @Test
    fun idleMapsToWaitingPreviewAndPresentationOptions() {
        val state = mapSnapshot()

        assertEquals(ConnectionUiState.Waiting, state.connection)
        assertFalse(state.preview.isLive)
        assertEquals(16.0f / 9.0f, state.preview.landscapeAspectRatio, ASPECT_RATIO_TOLERANCE)
        assertTrue(state.settings.codecOptions.any { it.isSelected })
        assertTrue(state.settings.profileOptions.any { it.isSelected })
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
                session = dev.mobilewebcam.sender.model.NegotiatedSession(
                    sessionId = "session",
                    endpoint = dev.mobilewebcam.sender.model.ReceiverEndpoint("desktop", 50000),
                    selectedCodec = dev.mobilewebcam.sender.model.VideoCodec.H264,
                    profile = VideoProfiles.default,
                    bitrateBps = VideoProfiles.default.h264BitrateBps,
                    mediaPort = 50001,
                    outputPixelFormat = dev.mobilewebcam.sender.model.OutputPixelFormat.NV12,
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
    ) = SenderScreenStateMapper.map(
        SenderDomainSnapshot(
            codecPreference = CodecPreference.AUTO_PREFER_H265,
            profile = VideoProfiles.default,
            cameraInteraction = CameraInteractionState(),
            streamState = streamState,
            cameraPermissionGranted = true,
            activeReceiverName = activeReceiverName,
            validationMessage = null,
            isScreenDimmed = false,
            isZoomTrayOpen = false,
            isPermissionDialogOpen = isPermissionDialogOpen,
        ),
    )

    private companion object {
        const val ASPECT_RATIO_TOLERANCE = 0.0001f
    }
}
