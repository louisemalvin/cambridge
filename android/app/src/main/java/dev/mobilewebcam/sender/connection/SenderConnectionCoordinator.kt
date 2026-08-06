package dev.mobilewebcam.sender.connection

import dev.mobilewebcam.sender.deployment.DirectDeployment
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.StreamSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

class SenderConnectionCoordinator(
    private val controller: StreamSessionController,
    private val settings: SenderSettingsRepository,
    private val logger: AppLogger = AndroidAppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val defaultEndpoint: ReceiverEndpoint = DirectDeployment.endpoint,
    private val networkChangeMonitor: NetworkChangeMonitor = NoopNetworkChangeMonitor,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    private val jitterSource: () -> Double = { Random.nextDouble() },
) {
    private val mutex = Mutex()
    private val controllerOperationMutex = Mutex()
    private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
    private val activeReceiverNameFlow = MutableStateFlow(settings.state.value.receiverEndpoint?.displayName)
    private val configuredReceiverFlow = MutableStateFlow(settings.state.value.receiverEndpoint != null)
    private val recoveryWakeupFlow = MutableSharedFlow<Unit>(extraBufferCapacity = RECOVERY_WAKEUP_BUFFER_CAPACITY)
    private var endpoint: ReceiverEndpoint? = settings.state.value.receiverEndpoint
    private var desiredStreaming = false
    private var recoveryJob: Job? = null

    val streamState: StateFlow<StreamState> = stateFlow.asStateFlow()
    val activeReceiverName: StateFlow<String?> = activeReceiverNameFlow.asStateFlow()
    val hasConfiguredReceiver: StateFlow<Boolean> = configuredReceiverFlow.asStateFlow()

    init {
        networkChangeMonitor.start()
        scope.coroutineContext[Job]?.invokeOnCompletion {
            networkChangeMonitor.stop()
        }
        scope.launch {
            controller.state.collectLatest { controllerState ->
                val shouldRecover = mutex.withLock {
                    if (!desiredStreaming) {
                        if (stateFlow.value != StreamState.Stopping) {
                            stateFlow.value = controllerState
                        }
                        false
                    } else if (controllerState is StreamState.Failed && controllerState.failure.isRecoverable()) {
                        stateFlow.value = StreamState.Reconnecting
                        true
                    } else {
                        stateFlow.value = controllerState
                        false
                    }
                }
                if (shouldRecover) {
                    ensureRecoveryJob()
                }
            }
        }
        scope.launch {
            networkChangeMonitor.changes.collect {
                handleNetworkChange()
            }
        }
    }

    suspend fun connectToReceiver(receiverEndpoint: ReceiverEndpoint): Result<Unit> {
        val canStart = mutex.withLock {
            if (!receiverEndpoint.isValid()) {
                return@withLock false
            }
            if (desiredStreaming || stateFlow.value == StreamState.Connecting ||
                stateFlow.value is StreamState.Streaming || stateFlow.value == StreamState.Reconnecting
            ) {
                false
            } else {
                endpoint = receiverEndpoint
                settings.updateReceiverEndpoint(receiverEndpoint)
                configuredReceiverFlow.value = true
                activeReceiverNameFlow.value = receiverEndpoint.displayName
                desiredStreaming = true
                stateFlow.value = StreamState.Connecting
                true
            }
        }
        if (!canStart) {
            return Result.failure(IllegalStateException("A stream is already active"))
        }
        return startOnceAndScheduleRecovery()
    }

    suspend fun connect(): Result<Unit> =
        connectToReceiver(endpoint ?: defaultEndpoint)

    suspend fun startStream(): Result<Unit> = connect()

    suspend fun stop(): Result<Unit> {
        val job = mutex.withLock {
            desiredStreaming = false
            val runningRecovery = recoveryJob
            recoveryJob = null
            stateFlow.value = if (controller.state.value == StreamState.Idle) {
                StreamState.Idle
            } else {
                StreamState.Stopping
            }
            runningRecovery
        }
        job?.cancel()
        job?.join()
        val stopped = stopController()
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
            settings.updateReceiverEndpoint(null)
            stateFlow.value = StreamState.Idle
        }
        return Result.success(Unit)
    }

    private suspend fun handleNetworkChange() {
        val shouldRecover = mutex.withLock { desiredStreaming }
        if (!shouldRecover) return
        runCatching { stopController() }
        mutex.withLock {
            if (desiredStreaming) {
                stateFlow.value = StreamState.Reconnecting
            }
        }
        recoveryWakeupFlow.tryEmit(Unit)
        ensureRecoveryJob()
    }

    private suspend fun startOnceAndScheduleRecovery(): Result<Unit> {
        val result = startControllerOnce()
        val shouldRecover = mutex.withLock {
            if (result.isSuccess) {
                stateFlow.value = controller.state.value
                false
            } else if (desiredStreaming && result.exceptionOrNull()?.toStreamFailure().isRecoverable()) {
                stateFlow.value = StreamState.Reconnecting
                true
            } else {
                stateFlow.value = controller.state.value
                false
            }
        }
        if (shouldRecover) {
            ensureRecoveryJob()
        }
        return result
    }

    private fun ensureRecoveryJob() {
        scope.launch {
            mutex.withLock {
                if (recoveryJob?.isActive != true && desiredStreaming && endpoint != null) {
                    recoveryJob = scope.launch { recoverUntilStreaming() }
                }
            }
        }
    }

    private suspend fun recoverUntilStreaming() {
        var attempt = FIRST_RECOVERY_ATTEMPT
        while (true) {
            val retryDelay = reconnectPolicy.delayMillis(attempt, jitterSource())
            val wokenByNetwork = withTimeoutOrNull(retryDelay) {
                recoveryWakeupFlow.first()
                true
            } == true
            val shouldTry = mutex.withLock { desiredStreaming && endpoint != null }
            if (!shouldTry) return
            val result = startControllerOnce()
            if (result.isSuccess) {
                mutex.withLock { stateFlow.value = controller.state.value }
                return
            }
            val recoverable = mutex.withLock {
                desiredStreaming && result.exceptionOrNull()?.toStreamFailure().isRecoverable()
            }
            if (!recoverable) return
            attempt = if (wokenByNetwork) FIRST_RECOVERY_ATTEMPT else attempt + RECOVERY_ATTEMPT_STEP
        }
    }

    private suspend fun startControllerOnce(): Result<Unit> {
        val request = mutex.withLock {
            val target = endpoint ?: return@withLock null
            stateFlow.value = StreamState.Connecting
            StartRequest(target, settings.state.value.profile)
        } ?: return Result.failure(StreamFailureException(StreamFailure.ReceiverUnavailable("No desktop is configured")))
        return controllerOperationMutex.withLock {
            controller.start(endpoint = request.endpoint, profile = request.profile)
        }
    }

    private suspend fun stopController(): Result<Unit> = controllerOperationMutex.withLock {
        controller.stop()
    }

    private data class StartRequest(
        val endpoint: ReceiverEndpoint,
        val profile: dev.mobilewebcam.sender.model.VideoProfile,
    )

    private fun StreamFailure?.isRecoverable(): Boolean = when (this) {
        StreamFailure.NetworkDisconnected,
        is StreamFailure.ReceiverUnavailable,
        is StreamFailure.StreamStartFailed,
        -> true
        else -> false
    }

    private fun Throwable?.toStreamFailure(): StreamFailure? = when (this) {
        is StreamFailureException -> failure
        else -> null
    }

    private object NoopNetworkChangeMonitor : NetworkChangeMonitor {
        override val changes: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
        override fun start() = Unit
        override fun stop() = Unit
    }

    private companion object {
        const val RECOVERY_WAKEUP_BUFFER_CAPACITY = 1
        const val FIRST_RECOVERY_ATTEMPT = 0
        const val RECOVERY_ATTEMPT_STEP = 1
    }
}
