package dev.cambridge.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EmptyReceiverDiscovery : ReceiverDiscovery {
    private val state = MutableStateFlow(ReceiverDiscoverySnapshot.Stopped)

    override val snapshot: StateFlow<ReceiverDiscoverySnapshot> = state.asStateFlow()

    override fun start() = Unit

    override fun stop() = Unit
}
