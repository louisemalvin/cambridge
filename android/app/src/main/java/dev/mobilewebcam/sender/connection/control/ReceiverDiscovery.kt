package dev.mobilewebcam.sender.connection.control

import dev.mobilewebcam.sender.model.ReceiverEndpoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface ReceiverDiscovery {
    fun discover(): Flow<ReceiverEndpoint>
}

object EmptyReceiverDiscovery : ReceiverDiscovery {
    override fun discover(): Flow<ReceiverEndpoint> = emptyFlow()
}
