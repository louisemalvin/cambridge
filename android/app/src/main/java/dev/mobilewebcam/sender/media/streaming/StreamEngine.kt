package dev.mobilewebcam.sender.media.streaming

import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.SrtTransportEndpoint
import kotlinx.coroutines.flow.Flow

interface StreamEngine {
    val events: Flow<StreamEngineEvent>

    suspend fun prepare(configuration: StreamConfiguration): Result<Unit>

    suspend fun start(endpoint: SrtTransportEndpoint): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun release()
}
