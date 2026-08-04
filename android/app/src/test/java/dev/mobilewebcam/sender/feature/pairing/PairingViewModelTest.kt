package dev.mobilewebcam.sender.feature.pairing

import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.session.VideoProfiles
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingViewModelTest {
    @Test
    fun idleStateMapsToSearching() {
        val state = PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = StreamState.Idle,
                activeReceiverName = null,
            ),
        )

        assertEquals(PairingUiState.Searching(UiText.Plain("Looking for a nearby receiver")), state)
    }

    @Test
    fun checkingNegotiatingPreparingAndStartingStatesMapToConnecting() {
        val checking = PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = StreamState.CheckingReceiver,
                activeReceiverName = null,
            ),
        )
        val negotiating = PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = StreamState.Negotiating,
                activeReceiverName = null,
            ),
        )
        val preparing = PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = StreamState.Preparing(VideoCodec.H264, VideoProfiles.default),
                activeReceiverName = null,
            ),
        )
        val starting = PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = StreamState.Starting(
                    session = dev.mobilewebcam.sender.model.NegotiatedSession(
                        sessionId = "session",
                        endpoint = dev.mobilewebcam.sender.model.ReceiverEndpoint("127.0.0.1", 50_000),
                        selectedCodec = VideoCodec.H264,
                        profile = VideoProfiles.default,
                        bitrateBps = VideoProfiles.default.h264BitrateBps,
                        mediaPort = 50_001,
                        outputPixelFormat = dev.mobilewebcam.sender.model.OutputPixelFormat.YUY2,
                        warnings = emptyList(),
                    ),
                ),
                activeReceiverName = null,
            ),
        )

        assertTrue(checking is PairingUiState.Connecting)
        assertTrue(negotiating is PairingUiState.Connecting)
        assertTrue(preparing is PairingUiState.Connecting)
        assertTrue(starting is PairingUiState.Connecting)
    }

    @Test
    fun streamingStateMapsToConnected() {
        val state = PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = StreamState.Streaming(
                    session = dev.mobilewebcam.sender.model.NegotiatedSession(
                        sessionId = "session",
                        endpoint = dev.mobilewebcam.sender.model.ReceiverEndpoint("127.0.0.1", 50_000),
                        selectedCodec = VideoCodec.H264,
                        profile = VideoProfiles.default,
                        bitrateBps = VideoProfiles.default.h264BitrateBps,
                        mediaPort = 50_001,
                        outputPixelFormat = dev.mobilewebcam.sender.model.OutputPixelFormat.YUY2,
                        warnings = emptyList(),
                    ),
                    startedAtMillis = 1L,
                ),
                activeReceiverName = "Desktop PC",
            ),
        )

        assertEquals(PairingUiState.Connected(UiText.Plain("Desktop PC")), state)
    }

    @Test
    fun failureStateMapsToFailureMessage() {
        val state = PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = StreamState.Failed(StreamFailure.NetworkDisconnected),
                activeReceiverName = null,
            ),
        )

        assertEquals(PairingUiState.Failed(UiText.Plain("The receiver connection was lost")), state)
    }

    @Test
    fun streamingTransitionEmitsNavigationOnlyOnce() {
        val session = dev.mobilewebcam.sender.model.NegotiatedSession(
            sessionId = "session",
            endpoint = dev.mobilewebcam.sender.model.ReceiverEndpoint("127.0.0.1", 50_000),
            selectedCodec = VideoCodec.H264,
            profile = VideoProfiles.default,
            bitrateBps = VideoProfiles.default.h264BitrateBps,
            mediaPort = 50_001,
            outputPixelFormat = dev.mobilewebcam.sender.model.OutputPixelFormat.YUY2,
            warnings = emptyList(),
        )
        val streaming = StreamState.Streaming(session, startedAtMillis = 1L)

        assertEquals(PairingUiEffect.NavigateToWebcam, PairingUiEffectMapper.map(StreamState.Idle, streaming))
        assertEquals(null, PairingUiEffectMapper.map(streaming, streaming))
    }
}
