package dev.mobilewebcam.sender.connection

import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.ReceiverDemand
import dev.mobilewebcam.sender.model.ReceiverDemandEvent
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.StreamSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SenderConnectionCoordinator(
    private val controller: StreamSessionController,
    private val receiver: ReceiverControlClient,
    private val settings: SenderSettingsRepository,
    private val logger: AppLogger = AndroidAppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
    private val activeReceiverNameFlow = MutableStateFlow<String?>(null)
    private val configuredReceiverFlow = MutableStateFlow(settings.state.value.receiverEndpoint != null)
    private var activeEndpoint: ReceiverEndpoint? = null
    private var demandJob: Job? = null
    private var lastDemand: ReceiverDemandEvent? = null
    private var lastMediaStoppedAtMillis: Long? = null

    val streamState: StateFlow<StreamState> = stateFlow.asStateFlow()
    val activeReceiverName: StateFlow<String?> = activeReceiverNameFlow.asStateFlow()
    val hasConfiguredReceiver: StateFlow<Boolean> = configuredReceiverFlow.asStateFlow()

    init {
        scope.launch {
            controller.state.collectLatest { controllerState ->
                mutex.withLock { reflectControllerState(controllerState) }
            }
        }
    }

    suspend fun connectToReceiver(endpoint: ReceiverEndpoint): Result<Unit> = mutex.withLock {
        if (!endpoint.isValid()) {
            return@withLock Result.failure(
                IllegalArgumentException("Receiver host, name, and control port are required"),
            )
        }
        if (activeEndpoint != null || stateFlow.value !is StreamState.Idle) {
            return@withLock Result.failure(IllegalStateException("A receiver is already connected"))
        }

        receiver.healthV2(endpoint).getOrElse { error ->
            return@withLock Result.failure(error)
        }
        val capabilities = receiver.capabilitiesV2(endpoint).getOrElse { error ->
            return@withLock Result.failure(error)
        }
        if (capabilities.activeSession) {
            return@withLock Result.failure(
                IllegalStateException("Receiver already has an active media session"),
            )
        }

        settings.updateReceiverEndpoint(endpoint)
        configuredReceiverFlow.value = true
        activeEndpoint = endpoint
        activeReceiverNameFlow.value = endpoint.displayName
        lastDemand = null
        stateFlow.value = StreamState.ConnectedStandby
        logger.event(
            "connected_standby",
            mapOf("receiverName" to endpoint.displayName),
        )
        demandJob?.cancel()
        demandJob = scope.launch { runDemandSubscription(endpoint) }
        Result.success(Unit)
    }

    suspend fun stop(): Result<Unit> = mutex.withLock {
        disconnectLocked(clearSettings = false)
    }

    suspend fun forgetReceiver(): Result<Unit> = mutex.withLock {
        val stopped = disconnectLocked(clearSettings = true)
        if (stopped.isSuccess) {
            configuredReceiverFlow.value = false
        }
        stopped
    }

    private suspend fun runDemandSubscription(endpoint: ReceiverEndpoint) {
        while (scope.isActive) {
            try {
                receiver.demandEventsV2(endpoint).collect { event ->
                    handleDemand(endpoint, event)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger.warn("receiver demand subscription failed", error)
            }
            if (!scope.isActive) break
            handleSubscriptionLost(endpoint)
            delay(RECONNECT_DELAY_MILLIS)
        }
    }

    private suspend fun handleDemand(endpoint: ReceiverEndpoint, event: ReceiverDemandEvent) {
        mutex.withLock {
            if (activeEndpoint != endpoint || !shouldApply(event)) return@withLock
            lastDemand = event
            when (event.demand) {
                ReceiverDemand.ACTIVE -> startForDemandLocked(endpoint)
                ReceiverDemand.INACTIVE -> stopForDemandLocked()
            }
        }
    }

    private suspend fun startForDemandLocked(endpoint: ReceiverEndpoint) {
        waitForMediaRestartCooldown()
        val configuredSettings = settings.state.value
        if (controller.state.value !is StreamState.Idle) {
            controller.stop()
        }
        val started = controller.start(
            endpoint = endpoint,
            preference = configuredSettings.codecPreference,
            profile = configuredSettings.profile,
        )
        if (started.isFailure) {
            lastDemand = null
            logger.warn("receiver demand could not start media", started.exceptionOrNull())
        }
    }

    private suspend fun stopForDemandLocked() {
        controller.stop()
        lastMediaStoppedAtMillis = System.currentTimeMillis()
        stateFlow.value = if (activeEndpoint == null) StreamState.Idle else StreamState.ConnectedStandby
    }

    private suspend fun waitForMediaRestartCooldown() {
        val stoppedAtMillis = lastMediaStoppedAtMillis ?: return
        val elapsedMillis = System.currentTimeMillis() - stoppedAtMillis
        val remainingMillis = MEDIA_RESTART_COOLDOWN_MILLIS - elapsedMillis
        if (remainingMillis > 0) delay(remainingMillis)
        lastMediaStoppedAtMillis = null
    }

    private suspend fun handleSubscriptionLost(endpoint: ReceiverEndpoint) {
        mutex.withLock {
            if (activeEndpoint != endpoint) return@withLock
            controller.stop()
            lastDemand = null
            stateFlow.value = StreamState.Failed(
                dev.mobilewebcam.sender.model.StreamFailure.ReceiverUnavailable(
                    "Receiver demand connection was lost",
                ),
            )
        }
    }

    private fun shouldApply(event: ReceiverDemandEvent): Boolean {
        val previous = lastDemand ?: return true
        if (event.generation > previous.generation) return true
        if (event.generation < previous.generation) return false
        return previous.demand == ReceiverDemand.ACTIVE && event.demand == ReceiverDemand.INACTIVE
    }

    private fun reflectControllerState(controllerState: StreamState) {
        stateFlow.value = if (activeEndpoint != null && controllerState == StreamState.Idle) {
            if (stateFlow.value is StreamState.Failed) {
                stateFlow.value
            } else {
                StreamState.ConnectedStandby
            }
        } else {
            controllerState
        }
    }

    private suspend fun disconnectLocked(clearSettings: Boolean): Result<Unit> {
        demandJob?.cancel()
        demandJob = null
        val stopped = controller.stop()
        if (stopped.isFailure) return stopped
        activeEndpoint = null
        lastDemand = null
        lastMediaStoppedAtMillis = null
        activeReceiverNameFlow.value = null
        stateFlow.value = StreamState.Idle
        if (clearSettings) settings.updateReceiverEndpoint(null)
        return Result.success(Unit)
    }

    private companion object {
        const val RECONNECT_DELAY_MILLIS = 1_000L
        // RootEncoder closes its SRT socket asynchronously during release.
        const val MEDIA_RESTART_COOLDOWN_MILLIS = 1_000L
    }
}
