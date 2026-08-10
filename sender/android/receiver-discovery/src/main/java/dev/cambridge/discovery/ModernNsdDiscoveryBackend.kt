package dev.cambridge.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class ModernNsdDiscoveryBackend(
    private val nsdManager: NsdManager,
    private val callbackExecutor: Executor,
    private val serviceType: String,
    private val addressAttributePrefix: String?,
    private val maximumAddressAttributeCount: Int,
    private val addressFamily: ReceiverDiscoveryAddressFamily,
) : NsdDiscoveryBackend {
    private val lock = Any()
    private val serviceCallbacks = linkedMapOf<NsdServiceKey, NsdManager.ServiceInfoCallback>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var backendListener: NsdDiscoveryBackend.Listener? = null

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
        val state = synchronized(lock) {
            val callbacks = serviceCallbacks.values.toList()
            serviceCallbacks.clear()
            StopState(discoveryListener, callbacks).also {
                discoveryListener = null
                backendListener = null
            }
        }
        state.discoveryListener?.let { listener ->
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
        state.serviceCallbacks.forEach { callback ->
            runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
        }
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
                registerServiceInfoCallback(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val registrations = synchronized(lock) {
                    if (discoveryListener !== this) return
                    matchingRegistrations(serviceInfo).onEach { (key, _) ->
                        serviceCallbacks.remove(key)
                        backendListener?.onServiceLost(key)
                    }
                }
                registrations.forEach { (_, callback) ->
                    runCatching { nsdManager.unregisterServiceInfoCallback(callback) }
                }
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

    private fun registerServiceInfoCallback(serviceInfo: NsdServiceInfo) {
        val key = serviceInfo.toServiceKey()
        val callback = synchronized(lock) {
            if (discoveryListener == null || serviceCallbacks.containsKey(key)) return
            createServiceInfoCallback(key).also { serviceCallbacks[key] = it }
        }
        try {
            nsdManager.registerServiceInfoCallback(serviceInfo, callbackExecutor, callback)
        } catch (failure: Throwable) {
            synchronized(lock) {
                if (serviceCallbacks[key] === callback) serviceCallbacks.remove(key)
            }
            backendListener?.onFailure(
                NsdBackendFailure(
                    operation = ReceiverDiscoveryOperation.REGISTER_SERVICE_INFO,
                    errorCode = null,
                    message = failure.message ?: "Android NSD service resolution could not start",
                    isFatal = false,
                ),
            )
        }
    }

    private fun createServiceInfoCallback(serviceKey: NsdServiceKey): NsdManager.ServiceInfoCallback =
        object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                val listener = synchronized(lock) {
                    if (serviceCallbacks[serviceKey] !== this) return
                    serviceCallbacks.remove(serviceKey)
                    backendListener
                }
                listener?.onFailure(
                    NsdBackendFailure(
                        operation = ReceiverDiscoveryOperation.REGISTER_SERVICE_INFO,
                        errorCode = errorCode,
                        message = "Android NSD service resolution registration failed",
                        isFatal = false,
                    ),
                )
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val listener = synchronized(lock) {
                    if (serviceCallbacks[serviceKey] !== this) return
                    backendListener
                }
                listener?.onServiceUpdated(
                    ResolvedNsdService(
                        key = serviceKey,
                        port = serviceInfo.port,
                        hosts = resolvedAddressCandidates(
                            resolvedHosts = serviceInfo.hostAddresses.mapNotNull { address -> address.hostAddress },
                            attributes = serviceInfo.attributes,
                            addressAttributePrefix = addressAttributePrefix,
                            maximumAddressAttributeCount = maximumAddressAttributeCount,
                            addressFamily = addressFamily,
                        ),
                    ),
                )
            }

            override fun onServiceLost() {
                val listener = synchronized(lock) {
                    if (serviceCallbacks[serviceKey] !== this) return
                    backendListener
                }
                listener?.onServiceLost(serviceKey)
            }

            override fun onServiceInfoCallbackUnregistered() = Unit
        }

    private fun matchingRegistrations(
        serviceInfo: NsdServiceInfo,
    ): List<Map.Entry<NsdServiceKey, NsdManager.ServiceInfoCallback>> {
        val exactKey = serviceInfo.toServiceKey()
        serviceCallbacks.entries.firstOrNull { (key, _) -> key == exactKey }?.let { exact ->
            return listOf(exact)
        }
        return serviceCallbacks.entries.filter { (key, _) ->
            key.serviceName == serviceInfo.serviceName &&
                key.serviceType == serviceInfo.serviceType.normalizedServiceType()
        }
    }

    private fun NsdServiceInfo.toServiceKey(): NsdServiceKey = NsdServiceKey(
        serviceName = serviceName,
        serviceType = serviceType.normalizedServiceType(),
        networkHandle = network?.networkHandle,
    )

    private data class StopState(
        val discoveryListener: NsdManager.DiscoveryListener?,
        val serviceCallbacks: List<NsdManager.ServiceInfoCallback>,
    )
}
