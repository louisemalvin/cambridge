package dev.cambridge.discovery

import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle-scoped receiver discovery.
 *
 * Discovery proposes network endpoints only. Consumers must probe an endpoint
 * before treating it as a compatible or available receiver.
 */
interface ReceiverDiscovery {
    val snapshot: StateFlow<ReceiverDiscoverySnapshot>

    fun start()

    fun stop()
}

data class ReceiverDiscoveryConfig(
    val serviceType: String,
    val addressAttributePrefix: String? = null,
    val maximumAddressAttributeCount: Int = NO_ADDRESS_ATTRIBUTES,
    val addressFamily: ReceiverDiscoveryAddressFamily = ReceiverDiscoveryAddressFamily.ANY,
    val restartDelayMillis: Long = DEFAULT_RESTART_DELAY_MILLIS,
) {
    init {
        require(serviceType.isNotBlank()) { "A DNS-SD service type is required" }
        require(restartDelayMillis > NO_RESTART_DELAY_MILLIS) {
            "The discovery restart delay must be positive"
        }
        require(
            (addressAttributePrefix == null && maximumAddressAttributeCount == NO_ADDRESS_ATTRIBUTES) ||
                (!addressAttributePrefix.isNullOrBlank() && maximumAddressAttributeCount > NO_ADDRESS_ATTRIBUTES),
        ) {
            "Address attributes require a prefix and a positive maximum count"
        }
    }

    companion object {
        // A short delay avoids a tight loop when Android's NSD daemon is temporarily unavailable.
        const val DEFAULT_RESTART_DELAY_MILLIS = 1_000L
        private const val NO_RESTART_DELAY_MILLIS = 0L
        private const val NO_ADDRESS_ATTRIBUTES = 0
    }
}

enum class ReceiverDiscoveryAddressFamily {
    ANY,
    IPV4,
}

data class DiscoveredReceiverEndpoint(
    val serviceName: String,
    val host: String,
    val port: Int,
) {
    fun isValid(): Boolean =
        serviceName.isNotBlank() && host.isNotBlank() && port > UNSPECIFIED_PORT

    companion object {
        private const val UNSPECIFIED_PORT = 0
    }
}

data class ReceiverDiscoverySnapshot(
    val phase: ReceiverDiscoveryPhase,
    val endpoints: List<DiscoveredReceiverEndpoint> = emptyList(),
    val failure: ReceiverDiscoveryFailure? = null,
) {
    companion object {
        val Stopped = ReceiverDiscoverySnapshot(ReceiverDiscoveryPhase.STOPPED)
    }
}

enum class ReceiverDiscoveryPhase {
    STOPPED,
    STARTING,
    RUNNING,
    RETRY_WAIT,
}

data class ReceiverDiscoveryFailure(
    val operation: ReceiverDiscoveryOperation,
    val errorCode: Int?,
    val message: String,
)

enum class ReceiverDiscoveryOperation {
    START_DISCOVERY,
    STOP_DISCOVERY,
    REGISTER_SERVICE_INFO,
    RESOLVE_SERVICE,
}
