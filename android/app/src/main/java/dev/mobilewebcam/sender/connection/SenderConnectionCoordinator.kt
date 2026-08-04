package dev.mobilewebcam.sender.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.StreamSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SenderConnectionCoordinator(
    private val context: Context,
    private val controller: StreamSessionController,
    private val settings: SenderSettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val cameraPermissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    private val mutex = Mutex()
    private val activeReceiverNameFlow = MutableStateFlow<String?>(null)
    private val configuredReceiverFlow = MutableStateFlow(settings.state.value.receiverEndpoint != null)

    val streamState: StateFlow<StreamState> = controller.state
    val activeReceiverName: StateFlow<String?> = activeReceiverNameFlow.asStateFlow()
    val hasConfiguredReceiver: StateFlow<Boolean> = configuredReceiverFlow.asStateFlow()

    init {
        scope.launch {
            controller.state.collectLatest { state ->
                if (state == StreamState.Idle || state is StreamState.Failed) {
                    mutex.withLock { activeReceiverNameFlow.value = null }
                }
            }
        }
    }

    suspend fun connectToReceiver(endpoint: ReceiverEndpoint): Result<Unit> = mutex.withLock {
        if (!endpoint.isValid()) {
            return@withLock Result.failure(
                IllegalArgumentException("Receiver host, name, and control port are required"),
            )
        }
        if (activeReceiverNameFlow.value != null || controller.state.value !is StreamState.Idle) {
            return@withLock Result.failure(IllegalStateException("A stream is already active"))
        }
        if (!cameraPermissionChecker()) {
            return@withLock Result.failure(
                IllegalStateException("Camera permission is required on the phone"),
            )
        }

        val configuredSettings = settings.state.value
        val started = controller.start(
            endpoint = endpoint,
            preference = configuredSettings.codecPreference,
            profile = configuredSettings.profile,
        )
        if (started.isFailure) return@withLock started

        settings.updateReceiverEndpoint(endpoint)
        configuredReceiverFlow.value = true
        activeReceiverNameFlow.value = endpoint.displayName
        Result.success(Unit)
    }

    suspend fun stop(): Result<Unit> = mutex.withLock {
        val stopped = controller.stop()
        if (stopped.isSuccess) activeReceiverNameFlow.value = null
        stopped
    }

    suspend fun forgetReceiver(): Result<Unit> = mutex.withLock {
        val stopped = controller.stop()
        if (stopped.isFailure) return@withLock stopped
        settings.updateReceiverEndpoint(null)
        configuredReceiverFlow.value = false
        activeReceiverNameFlow.value = null
        Result.success(Unit)
    }
}
