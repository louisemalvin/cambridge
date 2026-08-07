package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamSession
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.isSessionActive
import dev.mobilewebcam.sender.model.requiresStopConfirmation
import dev.mobilewebcam.sender.session.VideoProfiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStateTest {
    @Test
    fun connectingStreamingAndStoppingKeepTheSessionActive() {
        assertTrue(StreamState.Connecting.isSessionActive)
        assertTrue(StreamState.Stopping.isSessionActive)

        val streaming = StreamState.Streaming(
            session = streamingSession(),
            startedAtMillis = 0,
        )
        assertTrue(streaming.isSessionActive)
    }

    @Test
    fun idleAndFailedHaveNoActiveSession() {
        assertFalse(StreamState.Idle.isSessionActive)
        assertFalse(StreamState.Failed(StreamFailure.NetworkDisconnected).isSessionActive)
    }

    @Test
    fun stoppingDoesNotAskForASecondStopConfirmation() {
        assertTrue(StreamState.Connecting.requiresStopConfirmation)
        assertTrue(StreamState.Streaming(streamingSession(), 0).requiresStopConfirmation)
        assertFalse(StreamState.Stopping.requiresStopConfirmation)
    }

    private fun streamingSession() = StreamSession(
        sessionId = "session",
        endpoint = ReceiverEndpoint("desktop", 50_000),
        selectedCodec = VideoCodec.H264,
        profile = VideoProfiles.default,
        bitrateBps = VideoProfiles.default.h264BitrateBps,
        mediaPort = 50_001,
        outputPixelFormat = OutputPixelFormat.NV12,
        warnings = emptyList(),
    )
}
