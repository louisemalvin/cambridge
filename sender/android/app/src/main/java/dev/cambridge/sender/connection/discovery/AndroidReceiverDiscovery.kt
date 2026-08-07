package dev.cambridge.sender.connection.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.cambridge.sender.connection.control.ReceiverDiscovery
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.model.ReceiverEndpoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Suppress("DEPRECATION")
class AndroidReceiverDiscovery(
    context: Context,
) : ReceiverDiscovery {
    private val nsdManager = context.applicationContext.getSystemService(NsdManager::class.java)

    override fun discover(): Flow<ReceiverEndpoint> = callbackFlow {
        val resolvingServices = mutableSetOf<String>()
        lateinit var resolveListener: NsdManager.ResolveListener
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.matchesCamBridgeServiceType()) return
                if (resolvingServices.add(serviceInfo.serviceName)) {
                    runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                        .onFailure { resolvingServices.remove(serviceInfo.serviceName) }
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                resolvingServices.remove(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress
                if (host != null) {
                    val endpoint = ReceiverEndpoint(
                        host = host,
                        controlPort = serviceInfo.port,
                        displayName = serviceInfo.serviceName,
                    )
                    if (endpoint.isValid()) {
                        trySend(endpoint)
                    }
                }
                resolvingServices.remove(serviceInfo.serviceName)
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolvingServices.remove(serviceInfo.serviceName)
            }
        }

        runCatching {
            nsdManager.discoverServices(
                CamBridgeStreamContract.DISCOVERY_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener,
            )
        }.onFailure { close(it) }

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        }
    }.distinctUntilChanged()

    private fun String.matchesCamBridgeServiceType(): Boolean =
        trimEnd(SERVICE_TYPE_SEPARATOR) == CamBridgeStreamContract.DISCOVERY_SERVICE_TYPE

    private companion object {
        const val SERVICE_TYPE_SEPARATOR = '.'
    }
}
