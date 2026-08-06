package dev.mobilewebcam.sender.platform.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import dev.mobilewebcam.sender.connection.NetworkChangeMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AndroidNetworkChangeMonitor(
    context: Context,
) : NetworkChangeMonitor {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = CHANGE_BUFFER_CAPACITY)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: android.net.Network) {
            changesFlow.tryEmit(Unit)
        }

        override fun onLost(network: android.net.Network) {
            changesFlow.tryEmit(Unit)
        }

        override fun onCapabilitiesChanged(
            network: android.net.Network,
            networkCapabilities: android.net.NetworkCapabilities,
        ) {
            changesFlow.tryEmit(Unit)
        }
    }
    private var started = false

    override val changes: Flow<Unit> = changesFlow.asSharedFlow()

    @Synchronized
    override fun start() {
        if (started) return
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder().build(),
            callback,
        )
        started = true
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        started = false
    }

    private companion object {
        const val CHANGE_BUFFER_CAPACITY = 8
    }
}
