package dev.mobilewebcam.sender.connection.control

import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverHealth
import dev.mobilewebcam.sender.model.ReceiverDemandEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface ReceiverControlClient {
    suspend fun healthV2(endpoint: ReceiverEndpoint): Result<ReceiverHealth>
    suspend fun capabilitiesV2(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities>
    suspend fun createSessionV2(
        endpoint: ReceiverEndpoint,
        request: PrepareSessionRequest,
    ): Result<NegotiatedSession>
    suspend fun stopSessionV2(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit>
    suspend fun sessionStateV2(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit>

    fun demandEventsV2(endpoint: ReceiverEndpoint): Flow<ReceiverDemandEvent> = emptyFlow()
}
