package dev.mobilewebcam.sender.connection.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidReceiverDiscovery(
    context: Context,
) : ReceiverDiscovery {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val mutableState = MutableStateFlow<ReceiverDiscoveryState>(
        ReceiverDiscoveryState.Idle,
    )
    private val resolvedReceivers = linkedMapOf<String, DiscoveredReceiver>()
    private val resolvingServices = mutableSetOf<String>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    override val state: StateFlow<ReceiverDiscoveryState> = mutableState.asStateFlow()

    @Synchronized
    override fun start() {
        if (discoveryListener != null) return

        resolvedReceivers.clear()
        resolvingServices.clear()
        mutableState.value = ReceiverDiscoveryState.Searching
        val listener = discoveryListener()
        discoveryListener = listener
        runCatching {
            nsdManager.discoverServices(
                ReceiverDiscoveryContract.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        }.onFailure {
            discoveryListener = null
            mutableState.value = ReceiverDiscoveryState.Failed(
                it.message ?: DISCOVERY_START_FAILED,
            )
        }
    }

    @Synchronized
    override fun stop() {
        val listener = discoveryListener
        discoveryListener = null
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        resolvedReceivers.clear()
        resolvingServices.clear()
        mutableState.value = ReceiverDiscoveryState.Idle
    }

    @Synchronized
    private fun onServiceFound(serviceInfo: NsdServiceInfo) {
        if (discoveryListener == null) return
        val serviceName = serviceInfo.serviceName
        if (resolvingServices.add(serviceName)) {
            resolveServiceCompat(serviceInfo).onFailure {
                resolvingServices.remove(serviceName)
            }
        }
    }

    @Synchronized
    private fun onServiceLost(serviceInfo: NsdServiceInfo) {
        onServiceLost(serviceInfo.serviceName)
    }

    @Synchronized
    private fun onServiceLost(serviceName: String) {
        if (discoveryListener == null) return
        resolvingServices.remove(serviceName)
        resolvedReceivers.remove(serviceName)
        publishReceivers()
    }

    @Synchronized
    private fun onServiceResolved(serviceInfo: NsdServiceInfo) {
        if (discoveryListener == null) return
        val serviceName = serviceInfo.serviceName
        resolvingServices.remove(serviceName)
        val endpoint = ReceiverDiscoveryContract.endpointFrom(
            serviceName = serviceName,
            host = resolvedHost(serviceInfo),
            port = serviceInfo.port,
            attributes = serviceInfo.attributes,
        ) ?: return
        resolvedReceivers[serviceName] = DiscoveredReceiver(serviceName, endpoint)
        publishReceivers()
    }

    @Synchronized
    private fun publishReceivers() {
        val receivers = resolvedReceivers.values
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.endpoint.displayName })
        mutableState.value = if (receivers.isEmpty()) {
            ReceiverDiscoveryState.Searching
        } else {
            ReceiverDiscoveryState.Available(receivers)
        }
    }

    private fun discoveryListener(): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                mutableState.value = ReceiverDiscoveryState.Searching
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                this@AndroidReceiverDiscovery.onServiceFound(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                this@AndroidReceiverDiscovery.onServiceLost(serviceInfo)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                if (discoveryListener != null) {
                    mutableState.value = ReceiverDiscoveryState.Idle
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@AndroidReceiverDiscovery) {
                    discoveryListener = null
                    mutableState.value = ReceiverDiscoveryState.Failed(
                        "mDNS discovery failed with code $errorCode",
                    )
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                synchronized(this@AndroidReceiverDiscovery) {
                    discoveryListener = null
                    mutableState.value = ReceiverDiscoveryState.Failed(
                        "mDNS discovery stop failed with code $errorCode",
                    )
                }
            }
        }

    private fun resolveListener(): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(this@AndroidReceiverDiscovery) {
                    resolvingServices.remove(serviceInfo.serviceName)
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                this@AndroidReceiverDiscovery.onServiceResolved(serviceInfo)
            }
        }

    private companion object {
        const val DISCOVERY_START_FAILED = "mDNS discovery could not start"
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceCompat(serviceInfo: NsdServiceInfo): Result<Unit> = runCatching {
        nsdManager.resolveService(serviceInfo, resolveListener())
    }

    @Suppress("DEPRECATION")
    private fun resolvedHost(serviceInfo: NsdServiceInfo): String? = serviceInfo.host?.hostAddress
}
