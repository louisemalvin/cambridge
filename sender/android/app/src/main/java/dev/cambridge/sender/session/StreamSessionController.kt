package dev.cambridge.sender.session

import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.VideoProfile
import kotlinx.coroutines.flow.StateFlow

interface StreamSessionController {
    val state: StateFlow<StreamState>

    suspend fun start(
        endpoint: ReceiverEndpoint,
        profile: VideoProfile,
        orientation: StreamOrientation,
    ): Result<Unit>

    suspend fun start(
        endpoint: ReceiverEndpoint,
        profile: VideoProfile,
        orientation: StreamOrientation,
        bitrateBps: Int,
    ): Result<Unit> = start(endpoint, profile, orientation)

    suspend fun stop(): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>
}
