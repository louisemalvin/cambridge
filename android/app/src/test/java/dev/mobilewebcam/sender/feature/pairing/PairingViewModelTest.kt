package dev.mobilewebcam.sender.feature.pairing

import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingViewModelTest {
    @Test
    fun idleStateMapsToSearching() {
        val state = PairingUiStateMapper.map(
            PairingDomainSnapshot(StreamState.Idle, null),
        )

        assertEquals(PairingUiState.Searching(UiText.Plain("Connect to your OBS computer")), state)
    }

    @Test
    fun connectingStateMapsToConnecting() {
        val connecting = PairingUiStateMapper.map(
            PairingDomainSnapshot(StreamState.Connecting, null),
        )

        assertTrue(connecting is PairingUiState.Connecting)
    }

    @Test
    fun failureStateMapsToFailureMessage() {
        val state = PairingUiStateMapper.map(
            PairingDomainSnapshot(StreamState.Failed(StreamFailure.NetworkDisconnected), null),
        )

        assertEquals(PairingUiState.Failed(UiText.Plain("Connection lost. Press Connect to start again")), state)
    }

    @Test
    fun streamingTransitionEmitsNavigationOnlyOnce() {
        assertEquals(
            PairingUiEffect.NavigateToWebcam,
            PairingUiEffectMapper.map(StreamState.Connecting, streamingState()),
        )
        assertEquals(
            null,
            PairingUiEffectMapper.map(streamingState(), streamingState()),
        )
    }

    private fun streamingState() = StreamState.Streaming(
        session = dev.mobilewebcam.sender.model.StreamSession(
            sessionId = "session",
            endpoint = dev.mobilewebcam.sender.model.ReceiverEndpoint("127.0.0.1", 50_000),
            selectedCodec = dev.mobilewebcam.sender.model.VideoCodec.H264,
            profile = dev.mobilewebcam.sender.session.VideoProfiles.default,
            bitrateBps = dev.mobilewebcam.sender.session.VideoProfiles.default.h264BitrateBps,
            mediaPort = 50_001,
            outputPixelFormat = dev.mobilewebcam.sender.model.OutputPixelFormat.NV12,
            warnings = emptyList(),
        ),
        startedAtMillis = 1L,
    )
}
