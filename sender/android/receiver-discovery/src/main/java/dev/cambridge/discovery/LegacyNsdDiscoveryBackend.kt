package dev.cambridge.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.ArrayDeque

@Suppress("DEPRECATION")
internal class LegacyNsdDiscoveryBackend(
    private val nsdManager: NsdManager,
    private val serviceType: String,
    private val addressAttributePrefix: String?,
    private val maximumAddressAttributeCount: Int,
    private val addressFamily: ReceiverDiscoveryAddressFamily,
) : NsdDiscoveryBackend {
    private val lock = Any()
    private val knownServiceKeys = mutableSetOf<NsdServiceKey>()
    private val pendingServices = ArrayDeque<PendingService>()
    private var activeService: PendingService? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var backendListener: NsdDiscoveryBackend.Listener? = null

    private val resolveListener = object : NsdManager.ResolveListener {
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            val resolved = synchronized(lock) {
                val service = activeService ?: return
                activeService = null
                if (service.key !in knownServiceKeys) {
                    resolveNextLocked()
                    return
                }
                val hosts = resolvedAddressCandidates(
                    resolvedHosts = listOfNotNull(serviceInfo.host?.hostAddress),
                    attributes = serviceInfo.attributes,
                    addressAttributePrefix = addressAttributePrefix,
                    maximumAddressAttributeCount = maximumAddressAttributeCount,
                    addressFamily = addressFamily,
                )
                val result = hosts.takeIf(List<String>::isNotEmpty)?.let {
                    ResolvedNsdService(
                        key = service.key,
                        port = serviceInfo.port,
                        hosts = it,
                    )
                }
                resolveNextLocked()
                result
            }
            resolved?.let { backendListener?.onServiceUpdated(it) }
        }

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            val listener = synchronized(lock) {
                val service = activeService
                activeService = null
                service?.let { knownServiceKeys.remove(it.key) }
                resolveNextLocked()
                backendListener
            }
            listener?.onFailure(
                NsdBackendFailure(
                    operation = ReceiverDiscoveryOperation.RESOLVE_SERVICE,
                    errorCode = errorCode,
                    message = "Android NSD could not resolve a discovered service",
                    isFatal = false,
                ),
            )
        }
    }

    override fun start(listener: NsdDiscoveryBackend.Listener) {
        val androidListener = synchronized(lock) {
            if (discoveryListener != null) return
            backendListener = listener
            createDiscoveryListener().also { discoveryListener = it }
        }
        try {
            nsdManager.discoverServices(
                serviceType,
                NsdManager.PROTOCOL_DNS_SD,
                androidListener,
            )
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (discoveryListener === androidListener) discoveryListener = null
            }
            throw failure
        }
    }

    override fun stop() {
        val listener = synchronized(lock) {
            val current = discoveryListener
            discoveryListener = null
            backendListener = null
            knownServiceKeys.clear()
            pendingServices.clear()
            activeService = null
            current
        }
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(discoveredServiceType: String) {
                synchronized(lock) {
                    if (discoveryListener !== this) return
                    backendListener?.onStarted()
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.normalizedServiceType() != serviceType.normalizedServiceType()) {
                    return
                }
                synchronized(lock) {
                    if (discoveryListener !== this) return
                    val pending = PendingService(
                        key = NsdServiceKey(
                            serviceName = serviceInfo.serviceName,
                            serviceType = serviceInfo.serviceType.normalizedServiceType(),
                            networkHandle = null,
                        ),
                        serviceInfo = serviceInfo,
                    )
                    if (knownServiceKeys.add(pending.key)) {
                        pendingServices.addLast(pending)
                        resolveNextLocked()
                    }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostKeys = synchronized(lock) {
                    if (discoveryListener !== this) return
                    val matches = knownServiceKeys.filter { key ->
                        key.serviceName == serviceInfo.serviceName &&
                            key.serviceType == serviceInfo.serviceType.normalizedServiceType()
                    }
                    knownServiceKeys.removeAll(matches.toSet())
                    pendingServices.removeAll { pending -> pending.key in matches }
                    matches
                }
                lostKeys.forEach { key -> backendListener?.onServiceLost(key) }
            }

            override fun onDiscoveryStopped(discoveredServiceType: String) {
                synchronized(lock) {
                    if (discoveryListener === this) discoveryListener = null
                }
            }

            override fun onStartDiscoveryFailed(discoveredServiceType: String, errorCode: Int) {
                val listener = synchronized(lock) {
                    if (discoveryListener !== this) return
                    discoveryListener = null
                    backendListener
                }
                listener?.onFailure(
                    NsdBackendFailure(
                        operation = ReceiverDiscoveryOperation.START_DISCOVERY,
                        errorCode = errorCode,
                        message = "Android NSD discovery failed to start",
                        isFatal = true,
                    ),
                )
            }

            override fun onStopDiscoveryFailed(discoveredServiceType: String, errorCode: Int) {
                val listener = synchronized(lock) {
                    if (discoveryListener !== this) return
                    discoveryListener = null
                    backendListener
                }
                listener?.onFailure(
                    NsdBackendFailure(
                        operation = ReceiverDiscoveryOperation.STOP_DISCOVERY,
                        errorCode = errorCode,
                        message = "Android NSD discovery failed to stop cleanly",
                        isFatal = true,
                    ),
                )
            }
        }

    private fun resolveNextLocked() {
        while (activeService == null && pendingServices.isNotEmpty()) {
            val pending = pendingServices.removeFirst()
            activeService = pending
            val started = runCatching {
                nsdManager.resolveService(pending.serviceInfo, resolveListener)
            }.isSuccess
            if (started) return
            activeService = null
            knownServiceKeys.remove(pending.key)
        }
    }

    private data class PendingService(
        val key: NsdServiceKey,
        val serviceInfo: NsdServiceInfo,
    )
}
