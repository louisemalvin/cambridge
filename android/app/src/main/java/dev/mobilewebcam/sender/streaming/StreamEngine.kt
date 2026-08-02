package dev.mobilewebcam.sender.streaming

import dev.mobilewebcam.sender.model.StreamConfiguration
import kotlinx.coroutines.flow.Flow

interface StreamEngine {
    val events: Flow<StreamEngineEvent>

    suspend fun prepare(configuration: StreamConfiguration): Result<Unit>

    suspend fun start(
        receiverHost: String,
        mediaPort: Int,
    ): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun release()
}
