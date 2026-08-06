package dev.mobilewebcam.sender.connection.control

import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverEndpoint

interface ReceiverProbe {
    suspend fun probe(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities>
}
