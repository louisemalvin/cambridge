package dev.mobilewebcam.sender.media.streaming.rootencoder

import com.pedro.common.ConnectChecker
import dev.mobilewebcam.sender.media.streaming.StreamEngineEvent
import kotlinx.coroutines.flow.MutableSharedFlow

internal class RootEncoderEventAdapter(
    private val events: MutableSharedFlow<StreamEngineEvent>,
) : ConnectChecker {
    override fun onConnectionStarted(url: String) {
        events.tryEmit(StreamEngineEvent.ConnectionStarted(url))
    }

    override fun onConnectionSuccess() {
        events.tryEmit(StreamEngineEvent.Connected)
    }

    override fun onConnectionFailed(reason: String) {
        events.tryEmit(StreamEngineEvent.ConnectionFailed(reason))
    }

    override fun onDisconnect() {
        events.tryEmit(StreamEngineEvent.Disconnected)
    }

    override fun onAuthError() {
        events.tryEmit(StreamEngineEvent.AuthenticationError)
    }

    override fun onAuthSuccess() {
        events.tryEmit(StreamEngineEvent.AuthenticationSucceeded)
    }

    override fun onNewBitrate(bitrate: Long) {
        events.tryEmit(StreamEngineEvent.BitrateChanged(bitrate))
    }
}
