package dev.mobilewebcam.sender.connection

import dev.mobilewebcam.sender.deployment.DirectDeployment
import dev.mobilewebcam.sender.connection.control.EmptyReceiverDiscovery
import dev.mobilewebcam.sender.connection.control.ReceiverDiscovery
import dev.mobilewebcam.sender.connection.control.ReceiverProbe
import dev.mobilewebcam.sender.connection.control.direct.DirectReceiverProbe
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverProbeState
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.StreamSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class SenderConnectionCoordinator(
    private val controller: StreamSessionController,
    private val settings: SenderSettingsRepository,
    private val logger: AppLogger = AndroidAppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val defaultEndpoint: ReceiverEndpoint = DirectDeployment.endpoint,
    private val receiverProbe: ReceiverProbe = DirectReceiverProbe(),
    private val receiverDiscovery: ReceiverDiscovery = EmptyReceiverDiscovery,
) {
    private val mutex = Mutex()
    private val controllerOperationMutex = Mutex()
    private val receiverProbeMutex = Mutex()
    private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
    private val activeReceiverNameFlow = MutableStateFlow(settings.state.value.receiverEndpoint?.displayName)
    private val configuredReceiverFlow = MutableStateFlow(settings.state.value.receiverEndpoint != null)
    private val receiverProbeStateFlow = MutableStateFlow<ReceiverProbeState>(ReceiverProbeState.Idle)
    private var endpoint: ReceiverEndpoint? = settings.state.value.receiverEndpoint

    val streamState: StateFlow<StreamState> = stateFlow.asStateFlow()
    val activeReceiverName: StateFlow<String?> = activeReceiverNameFlow.asStateFlow()
    val hasConfiguredReceiver: StateFlow<Boolean> = configuredReceiverFlow.asStateFlow()
    val receiverProbeState: StateFlow<ReceiverProbeState> = receiverProbeStateFlow.asStateFlow()

    init {
        scope.launch {
            controller.state.collect { controllerState ->
                mutex.withLock { stateFlow.value = controllerState }
            }
        }
    }

    suspend fun connectToReceiver(receiverEndpoint: ReceiverEndpoint): Result<Unit> {
        val canStart = mutex.withLock {
            if (!receiverEndpoint.isValid()) {
                return@withLock false
            }
            if (stateFlow.value == StreamState.Connecting ||
                stateFlow.value is StreamState.Streaming ||
                stateFlow.value == StreamState.Stopping
            ) {
                false
            } else {
                endpoint = receiverEndpoint
                settings.updateReceiverEndpoint(receiverEndpoint)
                configuredReceiverFlow.value = true
                activeReceiverNameFlow.value = receiverEndpoint.displayName
                stateFlow.value = StreamState.Connecting
                true
            }
        }
        if (!canStart) {
            return Result.failure(IllegalStateException("A stream is already active"))
        }
        return startControllerOnce(receiverEndpoint)
    }

    suspend fun connect(): Result<Unit> =
        connectToReceiver(endpoint ?: defaultEndpoint)

    suspend fun startStream(): Result<Unit> = connect()

    suspend fun probeReceiver(): Result<ReceiverCapabilities> = receiverProbeMutex.withLock {
        val target = endpoint ?: settings.state.value.receiverEndpoint ?: discoverReceiver() ?: defaultEndpoint
        if (!target.isValid()) {
            val failure = IllegalArgumentException("The configured receiver endpoint is invalid")
            receiverProbeStateFlow.value = ReceiverProbeState.Unavailable(target, failure.message.orEmpty())
            return@withLock Result.failure(failure)
        }
        receiverProbeStateFlow.value = ReceiverProbeState.Checking
        receiverProbe.probe(target).onSuccess { capabilities ->
            val resolvedEndpoint = target.copy(displayName = capabilities.displayName)
            mutex.withLock {
                endpoint = resolvedEndpoint
                activeReceiverNameFlow.value = capabilities.displayName
            }
            logger.info(
                "receiver probe succeeded",
                mapOf(
                    "host" to target.host,
                    "controlPort" to target.controlPort,
                    "receiverId" to capabilities.receiverId,
                    "profiles" to capabilities.profileIds.joinToString(","),
                ),
            )
            receiverProbeStateFlow.value = ReceiverProbeState.Available(resolvedEndpoint, capabilities)
        }.onFailure { failure ->
            logger.warn(
                "receiver probe failed",
                failure,
                mapOf("host" to target.host, "controlPort" to target.controlPort),
            )
            receiverProbeStateFlow.value = ReceiverProbeState.Unavailable(
                endpoint = target,
                reason = failure.message ?: "The receiver did not respond",
            )
        }
    }

    private suspend fun discoverReceiver(): ReceiverEndpoint? = try {
        withTimeoutOrNull(DirectStreamContract.REQUEST_TIMEOUT_MILLIS.toLong()) {
            receiverDiscovery.discover().first()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    suspend fun stop(): Result<Unit> {
        mutex.withLock {
            stateFlow.value = if (controller.state.value == StreamState.Idle) {
                StreamState.Idle
            } else {
                StreamState.Stopping
            }
        }
        val stopped = controllerOperationMutex.withLock { controller.stop() }
        if (stopped.isFailure) return stopped
        mutex.withLock { stateFlow.value = StreamState.Idle }
        return Result.success(Unit)
    }

    suspend fun forgetReceiver(): Result<Unit> {
        val stopped = stop()
        if (stopped.isFailure) return stopped
        mutex.withLock {
            endpoint = null
            activeReceiverNameFlow.value = null
            configuredReceiverFlow.value = false
            receiverProbeStateFlow.value = ReceiverProbeState.Idle
            settings.updateReceiverEndpoint(null)
            stateFlow.value = StreamState.Idle
        }
        return Result.success(Unit)
    }

    private suspend fun startControllerOnce(receiverEndpoint: ReceiverEndpoint): Result<Unit> {
        val result = controllerOperationMutex.withLock {
            val configured = settings.state.value
            controller.start(
                endpoint = receiverEndpoint,
                profile = configured.profile,
                orientation = configured.streamOrientation,
            )
        }
        mutex.withLock { stateFlow.value = controller.state.value }
        logger.debug(
            "explicit stream connection completed",
            mapOf("success" to result.isSuccess, "host" to receiverEndpoint.host),
        )
        return result
    }
}
