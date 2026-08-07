package dev.cambridge.sender.media.streaming

import dev.cambridge.sender.model.StreamConfiguration
import dev.cambridge.sender.model.CamBridgeStreamEndpoint
import kotlinx.coroutines.flow.Flow

interface StreamEngine {
    val events: Flow<StreamEngineEvent>

    suspend fun prepare(configuration: StreamConfiguration): Result<Unit>

    suspend fun start(endpoint: CamBridgeStreamEndpoint): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun release()
}
