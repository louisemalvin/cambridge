package dev.mobilewebcam.sender.session

import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.VideoProfile
import kotlinx.coroutines.flow.StateFlow

interface StreamSessionController {
    val state: StateFlow<StreamState>

    suspend fun start(
        endpoint: ReceiverEndpoint,
        profile: VideoProfile,
        orientation: StreamOrientation,
    ): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>
}
