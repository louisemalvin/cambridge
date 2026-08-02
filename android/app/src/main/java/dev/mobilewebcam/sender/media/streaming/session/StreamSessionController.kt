package dev.mobilewebcam.sender.media.streaming.session

import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import kotlinx.coroutines.flow.StateFlow

interface StreamSessionController {
    val state: StateFlow<StreamState>

    suspend fun start(
        endpoint: ReceiverEndpoint,
        preference: CodecPreference,
        profile: VideoProfile,
    ): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>
}
