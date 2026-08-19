package dev.cambridge.sender.connection

import dev.cambridge.discovery.DiscoveredReceiverEndpoint
import dev.cambridge.discovery.EmptyReceiverDiscovery
import dev.cambridge.discovery.ReceiverDiscovery
import dev.cambridge.discovery.ReceiverDiscoveryFailure
import dev.cambridge.discovery.ReceiverDiscoveryPhase
import dev.cambridge.sender.deployment.CamBridgeDeployment
import dev.cambridge.sender.connection.control.ReceiverProbe
import dev.cambridge.sender.connection.control.cambridge.CamBridgeReceiverProbe
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.logging.AndroidAppLogger
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.model.ReceiverCandidate
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.ReceiverCapabilities
import dev.cambridge.sender.model.ReceiverProbeState
import dev.cambridge.sender.model.SenderSettingsRepository
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.isSessionActive
import dev.cambridge.sender.session.StreamSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SenderConnectionCoordinator(
    private val controller: StreamSessionController,
    private val settings: SenderSettingsRepository,
    private val logger: AppLogger = AndroidAppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val defaultEndpoint: ReceiverEndpoint = CamBridgeDeployment.endpoint,
    private val receiverProbe: ReceiverProbe = CamBridgeReceiverProbe(),
    private val receiverDiscovery: ReceiverDiscovery = EmptyReceiverDiscovery,
) {
    private val mutex = Mutex()
    private val controllerOperationMutex = Mutex()
    private val receiverProbeMutex = Mutex()
    private val receiverDiscoveryLifecycleLock = Any()
    private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
    private val activeReceiverNameFlow = MutableStateFlow(settings.state.value.receiverEndpoint?.displayName)
    private val receiverProbeStateFlow = MutableStateFlow<ReceiverProbeState>(ReceiverProbeState.Idle)
    private val receiverCandidatesFlow = MutableStateFlow<List<ReceiverCandidate>>(emptyList())
    private var endpoint: ReceiverEndpoint? = settings.state.value.receiverEndpoint
    private var receiverDiscoveryJob: Job? = null

    val streamState: StateFlow<StreamState> = stateFlow.asStateFlow()
    val activeReceiverName: StateFlow<String?> = activeReceiverNameFlow.asStateFlow()
    val receiverProbeState: StateFlow<ReceiverProbeState> = receiverProbeStateFlow.asStateFlow()
    val receiverCandidates: StateFlow<List<ReceiverCandidate>> = receiverCandidatesFlow.asStateFlow()

    init {
        scope.launch {
            controller.state.collect { controllerState ->
                mutex.withLock { stateFlow.value = controllerState }
            }
        }
    }

    suspend fun connectToReceiver(receiverEndpoint: ReceiverEndpoint): Result<Unit> {
        if (!receiverEndpoint.isValid()) {
            return Result.failure(IllegalArgumentException("Choose a valid OBS computer"))
        }
        val canStart = mutex.withLock {
            if (stateFlow.value.isSessionActive) {
                false
            } else {
                endpoint = receiverEndpoint
                settings.updateReceiverEndpoint(receiverEndpoint)
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

    suspend fun configureReceiverHost(host: String): Result<ReceiverCapabilities> {
        val candidate = mutex.withLock {
            val baseEndpoint = endpoint ?: settings.state.value.receiverEndpoint ?: defaultEndpoint
            baseEndpoint.copy(host = host.trim(), receiverId = null)
        }
        if (!candidate.isValid()) {
            val failure = IllegalArgumentException("Enter a valid receiver address")
            receiverProbeStateFlow.value = ReceiverProbeState.Unavailable(
                endpoint = candidate,
                reason = failure.message.orEmpty(),
            )
            return Result.failure(failure)
        }

        val configured = mutex.withLock {
            if (stateFlow.value.isSessionActive) {
                false
            } else {
                endpoint = candidate
                activeReceiverNameFlow.value = null
                true
            }
        }
        if (!configured) {
            return Result.failure(IllegalStateException("A stream is already active"))
        }
        return probeSpecificReceiver(candidate)
    }

    suspend fun selectReceiver(receiverId: String): Result<ReceiverCapabilities> = receiverProbeMutex.withLock {
        if (stateFlow.value.isSessionActive) {
            return@withLock Result.failure(IllegalStateException("Stop streaming before changing computers"))
        }
        val candidate = receiverCandidatesFlow.value.firstOrNull {
            it.capabilities.receiverId == receiverId
        } ?: return@withLock Result.failure(IllegalArgumentException("The selected OBS computer is unavailable"))
        applySelectedCandidate(candidate)
        Result.success(candidate.capabilities)
    }

    suspend fun connect(): Result<Unit> {
        val selectedEndpoint = mutex.withLock { endpoint }
            ?: return Result.failure(IllegalStateException("Choose an OBS computer before starting"))
        return connectToReceiver(selectedEndpoint)
    }

    suspend fun startStream(): Result<Unit> = connect()

    fun startReceiverDiscovery() {
        synchronized(receiverDiscoveryLifecycleLock) {
            if (receiverDiscoveryJob?.isActive == true) return
            receiverDiscovery.start()
            receiverDiscoveryJob = scope.launch {
                var previousEndpoints: List<DiscoveredReceiverEndpoint>? = null
                var previousFailure: ReceiverDiscoveryFailure? = null
                receiverDiscovery.snapshot.collect { snapshot ->
                    val newFailure = snapshot.failure?.takeIf { failure -> failure != previousFailure }
                    previousFailure = snapshot.failure
                    if (newFailure != null) {
                        logger.warn(
                            "receiver discovery reported an issue",
                            fields = mapOf(
                                "operation" to newFailure.operation,
                                "errorCode" to newFailure.errorCode,
                                "reason" to newFailure.message,
                            ),
                        )
                    }
                    if (snapshot.phase == ReceiverDiscoveryPhase.STOPPED ||
                        snapshot.endpoints == previousEndpoints
                    ) {
                        return@collect
                    }
                    previousEndpoints = snapshot.endpoints
                    probeReceiver()
                }
            }
        }
    }

    fun stopReceiverDiscovery() {
        val observation = synchronized(receiverDiscoveryLifecycleLock) {
            receiverDiscoveryJob.also { receiverDiscoveryJob = null }
        }
        observation?.cancel()
        receiverDiscovery.stop()
    }

    suspend fun probeReceiver(): Result<ReceiverCapabilities> = receiverProbeMutex.withLock {
        receiverProbeStateFlow.value = ReceiverProbeState.Checking
        val configuredEndpoint = mutex.withLock { endpoint } ?: settings.state.value.receiverEndpoint
        val discoveredEndpoints = receiverDiscovery.snapshot.value.endpoints.map { discovered ->
            discovered.toReceiverEndpoint()
        }
        val targets = buildList {
            addAll(discoveredEndpoints)
            configuredEndpoint?.let(::add)
            if (isEmpty()) add(defaultEndpoint)
        }.filter(ReceiverEndpoint::isValid)
            .distinctBy { target -> target.host to target.controlPort }
        val probeResults = coroutineScope {
            targets.map { target ->
                async { ProbedEndpoint(target, receiverProbe.probe(target)) }
            }.awaitAll()
        }
        val candidates = probeResults.mapNotNull { probed ->
            probed.result.fold(
                onSuccess = { capabilities ->
                    logProbeSuccess(probed.endpoint, capabilities)
                    ReceiverCandidate(
                        endpoint = probed.endpoint.copy(
                            displayName = capabilities.displayName,
                            receiverId = capabilities.receiverId,
                        ),
                        capabilities = capabilities,
                    )
                },
                onFailure = { null },
            )
        }.distinctBy { candidate -> candidate.capabilities.receiverId }
            .sortedWith(
                compareBy<ReceiverCandidate> { it.capabilities.displayName }
                    .thenBy { it.endpoint.host },
            )
        receiverCandidatesFlow.value = candidates

        val preferredCandidate = configuredEndpoint?.let { configured ->
            candidates.firstOrNull { candidate -> configured.matches(candidate) }
        }
        val selectedCandidate = preferredCandidate ?: candidates.singleOrNull()
        if (selectedCandidate != null) {
            applySelectedCandidate(selectedCandidate)
            return@withLock Result.success(selectedCandidate.capabilities)
        }
        mutex.withLock {
            endpoint = null
            activeReceiverNameFlow.value = null
        }
        if (candidates.isNotEmpty()) {
            receiverProbeStateFlow.value = ReceiverProbeState.SelectionRequired
            return@withLock Result.failure(IllegalStateException("Choose an OBS computer"))
        }

        probeResults.forEach { probed ->
            probed.result.exceptionOrNull()?.let { failure ->
                logProbeFailure(probed.endpoint, failure)
            }
        }

        val target = configuredEndpoint ?: targets.firstOrNull() ?: defaultEndpoint
        val failure = probeResults.firstNotNullOfOrNull { it.result.exceptionOrNull() }
            ?: IllegalStateException("No OBS computer was found")
        receiverProbeStateFlow.value = ReceiverProbeState.Unavailable(
            endpoint = target,
            reason = failure.message ?: "The receiver did not respond",
        )
        Result.failure(failure)
    }

    private suspend fun probeSpecificReceiver(target: ReceiverEndpoint): Result<ReceiverCapabilities> =
        receiverProbeMutex.withLock {
            receiverProbeStateFlow.value = ReceiverProbeState.Checking
            receiverProbe.probe(target).onSuccess { capabilities ->
                logProbeSuccess(target, capabilities)
                val candidate = ReceiverCandidate(
                    endpoint = target.copy(
                        displayName = capabilities.displayName,
                        receiverId = capabilities.receiverId,
                    ),
                    capabilities = capabilities,
                )
                receiverCandidatesFlow.value = (receiverCandidatesFlow.value + candidate)
                    .distinctBy { it.capabilities.receiverId }
                    .sortedWith(
                        compareBy<ReceiverCandidate> { it.capabilities.displayName }
                            .thenBy { it.endpoint.host },
                    )
                applySelectedCandidate(candidate)
            }.onFailure { failure ->
                logProbeFailure(target, failure)
                mutex.withLock {
                    endpoint = null
                    activeReceiverNameFlow.value = null
                }
                receiverProbeStateFlow.value = if (receiverCandidatesFlow.value.isEmpty()) {
                    ReceiverProbeState.Unavailable(
                        endpoint = target,
                        reason = failure.message ?: "The receiver did not respond",
                    )
                } else {
                    ReceiverProbeState.SelectionRequired
                }
            }
        }

    private suspend fun applySelectedCandidate(candidate: ReceiverCandidate) {
        mutex.withLock {
            endpoint = candidate.endpoint
            settings.updateReceiverEndpoint(candidate.endpoint)
            activeReceiverNameFlow.value = candidate.capabilities.displayName
        }
        receiverProbeStateFlow.value = ReceiverProbeState.Available(
            endpoint = candidate.endpoint,
            capabilities = candidate.capabilities,
        )
    }

    private fun ReceiverEndpoint.matches(candidate: ReceiverCandidate): Boolean =
        receiverId?.let { it == candidate.capabilities.receiverId }
            ?: (host == candidate.endpoint.host && controlPort == candidate.endpoint.controlPort)

    private fun DiscoveredReceiverEndpoint.toReceiverEndpoint(): ReceiverEndpoint = ReceiverEndpoint(
        host = host,
        controlPort = port,
        displayName = serviceName,
    )

    private fun logProbeSuccess(
        target: ReceiverEndpoint,
        capabilities: ReceiverCapabilities,
    ) {
        logger.info(
            "receiver probe succeeded",
            mapOf(
                "host" to target.host,
                "controlPort" to target.controlPort,
                "receiverId" to capabilities.receiverId,
                "maxLongEdge" to capabilities.maxLongEdge,
                "maxShortEdge" to capabilities.maxShortEdge,
            ),
        )
    }

    private fun logProbeFailure(target: ReceiverEndpoint, failure: Throwable) {
        logger.warn(
            "receiver probe failed",
            failure,
            mapOf("host" to target.host, "controlPort" to target.controlPort),
        )
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

    private suspend fun startControllerOnce(receiverEndpoint: ReceiverEndpoint): Result<Unit> {
        val result = controllerOperationMutex.withLock {
            val configured = settings.state.value
            controller.start(
                endpoint = receiverEndpoint,
                profile = configured.profile,
                orientation = configured.streamOrientation,
                bitrateBps = configured.bitrateBps,
            )
        }
        mutex.withLock { stateFlow.value = controller.state.value }
        logger.debug(
            "explicit stream connection completed",
            mapOf("success" to result.isSuccess, "host" to receiverEndpoint.host),
        )
        return result
    }

    private data class ProbedEndpoint(
        val endpoint: ReceiverEndpoint,
        val result: Result<ReceiverCapabilities>,
    )
}
