package dev.cambridge.sender

import dev.cambridge.sender.model.OutputPixelFormat
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamSession
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.isSessionActive
import dev.cambridge.sender.model.requiresStopConfirmation
import dev.cambridge.sender.session.VideoProfiles
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
        bitrateBps = VideoProfiles.default.defaultBitrateBps,
        mediaPort = 50_001,
        outputPixelFormat = OutputPixelFormat.NV12,
        warnings = emptyList(),
    )
}
