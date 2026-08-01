package dev.mobilewebcam.sender.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities

data class NetworkInformation(
    val transport: String,
    val addresses: List<String>,
)

interface NetworkInformationProvider {
    fun current(): List<NetworkInformation>
}

class AndroidNetworkInformationProvider(context: Context) : NetworkInformationProvider {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    override fun current(): List<NetworkInformation> = connectivityManager.allNetworks.mapNotNull { network ->
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@mapNotNull null
        val properties: LinkProperties = connectivityManager.getLinkProperties(network) ?: return@mapNotNull null
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        NetworkInformation(
            transport = transport,
            addresses = properties.linkAddresses.map { it.address.hostAddress.orEmpty() },
        )
    }
}
