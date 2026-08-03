package dev.mobilewebcam.sender.connection.discovery

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamState
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

data class PendingApproval(
    val receiverId: String,
    val receiverName: String,
)

class SenderConnectionCoordinator(
    private val context: Context,
    private val controller: StreamSessionController,
    private val pairings: PairingStore,
    private val settings: SenderSettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val mutex = Mutex()
    private val pendingFlow = MutableStateFlow<PendingApproval?>(null)
    private val activeReceiverNameFlow = MutableStateFlow<String?>(null)
    private val approvedReceiverFlow = MutableStateFlow(pairings.hasApprovedReceivers())
    private var pendingConnection: PendingConnection? = null
    private val rejectedReceivers = mutableSetOf<String>()
    private var activeReceiverId: String? = null

    val streamState: StateFlow<StreamState> = controller.state
    val pendingApproval: StateFlow<PendingApproval?> = pendingFlow.asStateFlow()
    val activeReceiverName: StateFlow<String?> = activeReceiverNameFlow.asStateFlow()
    val hasApprovedReceiver: StateFlow<Boolean> = approvedReceiverFlow.asStateFlow()

    init {
        scope.launch {
            controller.state.collectLatest { state ->
                if (state == StreamState.Idle || state is StreamState.Failed) {
                    mutex.withLock {
                        activeReceiverId = null
                        activeReceiverNameFlow.value = null
                    }
                }
            }
        }
    }

    suspend fun handleStartRequest(
        request: StartStreamRequestDto,
        peerAddress: String,
    ): StartStreamResponseDto = mutex.withLock {
        if (!request.isValid()) {
            return@withLock response(
                StartStreamStatusDto.INVALID_REQUEST,
                message = "Invalid or unsupported sender-control request",
            )
        }
        if (request.receiverId in rejectedReceivers) {
            return@withLock response(
                StartStreamStatusDto.REJECTED,
                message = "Connection was rejected on the phone",
            )
        }
        val active = activeReceiverId
        if (active != null && active != request.receiverId) {
            return@withLock response(
                StartStreamStatusDto.BUSY,
                message = "Phone is already streaming to another receiver",
            )
        }

        val authentication = pairings.authentication(
            request.receiverId,
            request.pairingToken,
            peerAddress,
        )
        if (authentication == PairingAuthentication.Unpaired) {
            pendingConnection = PendingConnection(request, peerAddress)
            pendingFlow.value = PendingApproval(request.receiverId, request.receiverName)
            return@withLock response(StartStreamStatusDto.APPROVAL_REQUIRED)
        }
        if (!hasCameraPermission()) {
            return@withLock response(
                StartStreamStatusDto.CAMERA_PERMISSION_REQUIRED,
                message = "Camera permission is required on the phone",
            )
        }
        if (active == request.receiverId) {
            return@withLock accepted(authentication)
        }

        val endpoint = ReceiverEndpoint(peerAddress, request.receiverControlPort)
        val configuredSettings = settings.state.value
        val started = controller.start(
            endpoint = endpoint,
            preference = configuredSettings.codecPreference,
            profile = configuredSettings.profile,
        )
        if (started.isFailure) {
            return@withLock response(
                StartStreamStatusDto.INVALID_REQUEST,
                message = started.exceptionOrNull()?.message ?: "Could not start the camera stream",
            )
        }
        activeReceiverId = request.receiverId
        activeReceiverNameFlow.value = request.receiverName
        pendingConnection = null
        pendingFlow.value = null
        accepted(authentication)
    }

    suspend fun approvePending() = mutex.withLock {
        val pending = pendingConnection ?: return@withLock
        rejectedReceivers.remove(pending.request.receiverId)
        pairings.approve(
            receiverId = pending.request.receiverId,
            receiverName = pending.request.receiverName,
            peerAddress = pending.peerAddress,
        )
        approvedReceiverFlow.value = true
        pendingConnection = null
        pendingFlow.value = null
    }

    suspend fun rejectPending() = mutex.withLock {
        val pending = pendingConnection ?: return@withLock
        rejectedReceivers.add(pending.request.receiverId)
        pendingConnection = null
        pendingFlow.value = null
    }

    suspend fun stop(): Result<Unit> = controller.stop()

    suspend fun forgetPairing(): Result<Unit> = mutex.withLock {
        val stopped = controller.stop()
        if (stopped.isFailure) return@withLock stopped
        pairings.forgetAll()
        approvedReceiverFlow.value = false
        pendingConnection = null
        pendingFlow.value = null
        rejectedReceivers.clear()
        activeReceiverId = null
        activeReceiverNameFlow.value = null
        stopped
    }

    private fun accepted(authentication: PairingAuthentication): StartStreamResponseDto {
        val pairingToken = when (authentication) {
            is PairingAuthentication.Authenticated -> null
            is PairingAuthentication.PendingTokenDelivery -> {
                pairings.markTokenDelivered(activeReceiverId ?: return response(
                    StartStreamStatusDto.INVALID_REQUEST,
                    message = "Pairing state was lost",
                ))
                authentication.token
            }
            PairingAuthentication.Unpaired -> null
        }
        return response(StartStreamStatusDto.ACCEPTED, pairingToken = pairingToken)
    }

    private fun response(
        status: StartStreamStatusDto,
        pairingToken: String? = null,
        message: String? = null,
    ) = StartStreamResponseDto(
        protocolVersion = SENDER_CONTROL_PROTOCOL_VERSION,
        senderId = pairings.senderId,
        status = status,
        pairingToken = pairingToken,
        message = message,
    )

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun StartStreamRequestDto.isValid(): Boolean =
            protocolVersion == SENDER_CONTROL_PROTOCOL_VERSION &&
            receiverId.isNotBlank() &&
            receiverName.isNotBlank() &&
            receiverControlPort in MIN_VALID_NETWORK_PORT..MAX_VALID_NETWORK_PORT

    private data class PendingConnection(
        val request: StartStreamRequestDto,
        val peerAddress: String,
    )
}
