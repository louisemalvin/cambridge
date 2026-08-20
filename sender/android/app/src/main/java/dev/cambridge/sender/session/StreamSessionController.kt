package dev.cambridge.sender.session

import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.StreamVideoConfiguration
import kotlinx.coroutines.flow.StateFlow

interface StreamSessionController {
    val state: StateFlow<StreamState>

    suspend fun start(
        endpoint: ReceiverEndpoint,
        configuration: StreamVideoConfiguration,
    ): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>
}
