package dev.cambridge.discovery

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AndroidReceiverDiscovery internal constructor(
    private val config: ReceiverDiscoveryConfig,
    private val backend: NsdDiscoveryBackend,
    private val scope: CoroutineScope,
) : ReceiverDiscovery {
    constructor(
        context: Context,
        config: ReceiverDiscoveryConfig,
    ) : this(
        config = config,
        backend = createBackend(context.applicationContext, config),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    private val lock = Any()
    private val registry = DiscoveryServiceRegistry()
    private val mutableSnapshot = MutableStateFlow(ReceiverDiscoverySnapshot.Stopped)
    private var isRequested = false
    private var retryJob: Job? = null

    override val snapshot: StateFlow<ReceiverDiscoverySnapshot> = mutableSnapshot.asStateFlow()

    private val backendListener = object : NsdDiscoveryBackend.Listener {
        override fun onStarted() {
            synchronized(lock) {
                if (!isRequested) return
                publish(phase = ReceiverDiscoveryPhase.RUNNING)
            }
        }

        override fun onServiceUpdated(service: ResolvedNsdService) {
            synchronized(lock) {
                if (!isRequested) return
                registry.update(service)
                publish(phase = ReceiverDiscoveryPhase.RUNNING)
            }
        }

        override fun onServiceLost(serviceKey: NsdServiceKey) {
            synchronized(lock) {
                if (!isRequested) return
                registry.remove(serviceKey)
                publish(phase = ReceiverDiscoveryPhase.RUNNING)
            }
        }

        override fun onFailure(failure: NsdBackendFailure) {
            val publicFailure = ReceiverDiscoveryFailure(
                operation = failure.operation,
                errorCode = failure.errorCode,
                message = failure.message,
            )
            if (!failure.isFatal) {
                synchronized(lock) {
                    if (!isRequested) return
                    publish(
                        phase = ReceiverDiscoveryPhase.RUNNING,
                        failure = publicFailure,
                    )
                }
                return
            }

            synchronized(lock) {
                if (!isRequested) return
                retryJob?.cancel()
                publish(
                    phase = ReceiverDiscoveryPhase.RETRY_WAIT,
                    failure = publicFailure,
                )
                retryJob = scope.launch {
                    delay(config.restartDelayMillis)
                    val shouldRestart = synchronized(lock) {
                        if (!isRequested) {
                            false
                        } else {
                            publish(
                                phase = ReceiverDiscoveryPhase.STARTING,
                                failure = publicFailure,
                            )
                            true
                        }
                    }
                    if (shouldRestart) startBackend()
                }
            }
        }
    }

    override fun start() {
        val shouldStart = synchronized(lock) {
            if (isRequested) {
                false
            } else {
                isRequested = true
                retryJob?.cancel()
                retryJob = null
                registry.clear()
                publish(phase = ReceiverDiscoveryPhase.STARTING)
                true
            }
        }
        if (shouldStart) startBackend()
    }

    override fun stop() {
        val shouldStop = synchronized(lock) {
            if (!isRequested && mutableSnapshot.value.phase == ReceiverDiscoveryPhase.STOPPED) {
                false
            } else {
                isRequested = false
                retryJob?.cancel()
                retryJob = null
                registry.clear()
                mutableSnapshot.value = ReceiverDiscoverySnapshot.Stopped
                true
            }
        }
        if (shouldStop) backend.stop()
    }

    private fun startBackend() {
        runCatching { backend.start(backendListener) }
            .onFailure { failure ->
                backendListener.onFailure(
                    NsdBackendFailure(
                        operation = ReceiverDiscoveryOperation.START_DISCOVERY,
                        errorCode = null,
                        message = failure.message ?: "Android NSD discovery could not start",
                        isFatal = true,
                    ),
                )
            }
    }

    private fun publish(
        phase: ReceiverDiscoveryPhase,
        failure: ReceiverDiscoveryFailure? = null,
    ) {
        mutableSnapshot.value = ReceiverDiscoverySnapshot(
            phase = phase,
            endpoints = registry.endpoints(),
            failure = failure,
        )
    }

    private companion object {
        fun createBackend(
            context: Context,
            config: ReceiverDiscoveryConfig,
        ): NsdDiscoveryBackend {
            val nsdManager = context.getSystemService(android.net.nsd.NsdManager::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ModernNsdDiscoveryBackend(
                    nsdManager = nsdManager,
                    callbackExecutor = ContextCompat.getMainExecutor(context),
                    serviceType = config.serviceType,
                    addressAttributePrefix = config.addressAttributePrefix,
                    maximumAddressAttributeCount = config.maximumAddressAttributeCount,
                    addressFamily = config.addressFamily,
                )
            } else {
                LegacyNsdDiscoveryBackend(
                    nsdManager = nsdManager,
                    serviceType = config.serviceType,
                    addressAttributePrefix = config.addressAttributePrefix,
                    maximumAddressAttributeCount = config.maximumAddressAttributeCount,
                    addressFamily = config.addressFamily,
                )
            }
        }
    }
}
