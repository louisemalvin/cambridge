package dev.cambridge.sender.connection.control

import dev.cambridge.sender.model.ReceiverCapabilities
import dev.cambridge.sender.model.ReceiverEndpoint

interface ReceiverProbe {
    suspend fun probe(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities>
}
