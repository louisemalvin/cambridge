package dev.mobilewebcam.sender.connection.control

import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverHealth

interface ReceiverControlClient {
    suspend fun health(endpoint: ReceiverEndpoint): Result<ReceiverHealth>
    suspend fun capabilities(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities>
    suspend fun prepareSession(
        endpoint: ReceiverEndpoint,
        request: PrepareSessionRequest,
    ): Result<NegotiatedSession>
    suspend fun stopSession(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit>
}
