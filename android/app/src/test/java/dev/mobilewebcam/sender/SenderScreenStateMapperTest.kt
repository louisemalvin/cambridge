package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.camera.CameraInteractionState
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.discovery.PendingApproval
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.ui.SenderDomainSnapshot
import dev.mobilewebcam.sender.ui.SenderScreenStateMapper
import dev.mobilewebcam.sender.ui.model.ConnectionUiState
import dev.mobilewebcam.sender.ui.model.SenderDialogUiState
import dev.mobilewebcam.sender.ui.model.UiText
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
    fun pendingApprovalMapsToAUiDialogWithoutExposingApprovalModel() {
        val state = mapSnapshot(
            pendingApproval = PendingApproval("receiver-id", "Test desktop"),
        )

        val dialog = state.dialog as SenderDialogUiState.PendingApproval
        assertEquals(UiText.Plain("Test desktop"), dialog.receiverName)
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

    private fun mapSnapshot(
        streamState: StreamState = StreamState.Idle,
        pendingApproval: PendingApproval? = null,
        activeReceiverName: String? = null,
    ) = SenderScreenStateMapper.map(
        SenderDomainSnapshot(
            codecPreference = CodecPreference.AUTO_PREFER_H265,
            profile = VideoProfiles.default,
            cameraInteraction = CameraInteractionState(),
            streamState = streamState,
            cameraPermissionGranted = true,
            pendingApproval = pendingApproval,
            activeReceiverName = activeReceiverName,
            validationMessage = null,
            isScreenDimmed = false,
            isSettingsOpen = false,
            isZoomTrayOpen = false,
            isPermissionDialogOpen = false,
        ),
    )

    private companion object {
        const val ASPECT_RATIO_TOLERANCE = 0.0001f
    }
}
