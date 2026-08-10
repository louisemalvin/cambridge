package dev.cambridge.discovery

internal interface NsdDiscoveryBackend {
    fun start(listener: Listener)

    fun stop()

    interface Listener {
        fun onStarted()

        fun onServiceUpdated(service: ResolvedNsdService)

        fun onServiceLost(serviceKey: NsdServiceKey)

        fun onFailure(failure: NsdBackendFailure)
    }
}

internal data class NsdServiceKey(
    val serviceName: String,
    val serviceType: String,
    val networkHandle: Long?,
)

internal data class ResolvedNsdService(
    val key: NsdServiceKey,
    val port: Int,
    val hosts: List<String>,
)

internal data class NsdBackendFailure(
    val operation: ReceiverDiscoveryOperation,
    val errorCode: Int?,
    val message: String,
    val isFatal: Boolean,
)

internal fun String.normalizedServiceType(): String = trimEnd(SERVICE_TYPE_SEPARATOR)

private const val SERVICE_TYPE_SEPARATOR = '.'
