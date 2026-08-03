package dev.mobilewebcam.sender.connection.discovery

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

data class PendingApproval(
    val receiverId: String,
    val receiverName: String,
    val streamId: String,
)

class SenderConnectionCoordinator(
    private val context: Context,
    private val controller: StreamSessionController,
    private val pairings: PairingStore,
    private val settings: SenderSettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val cameraPermissionChecker: () -> Boolean = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    private val mutex = Mutex()
    private val pendingFlow = MutableStateFlow<PendingApproval?>(null)
    private val activeReceiverNameFlow = MutableStateFlow<String?>(null)
    private val approvedReceiverFlow = MutableStateFlow(pairings.hasApprovedReceivers())
    private var pendingConnection: PendingConnection? = null
    private val rejectedReceivers = mutableSetOf<String>()
    private var activeConnection: ActiveConnection? = null
    private var lastStoppedStreamId: String? = null

    val streamState: StateFlow<StreamState> = controller.state
    val pendingApproval: StateFlow<PendingApproval?> = pendingFlow.asStateFlow()
    val activeReceiverName: StateFlow<String?> = activeReceiverNameFlow.asStateFlow()
    val hasApprovedReceiver: StateFlow<Boolean> = approvedReceiverFlow.asStateFlow()

    init {
        scope.launch {
            controller.state.collectLatest { state ->
                if (state == StreamState.Idle || state is StreamState.Failed) {
                    mutex.withLock {
                        activeConnection?.let { lastStoppedStreamId = it.streamId }
                        activeConnection = null
                        activeReceiverNameFlow.value = null
                    }
                }
            }
        }
    }

    suspend fun describeAvailability(): SenderAvailabilityDto = mutex.withLock {
        if (activeConnection == null) SenderAvailabilityDto.STANDBY else SenderAvailabilityDto.STREAMING
    }

    suspend fun handleStartRequest(
        request: StartStreamRequestDto,
        peerAddress: String,
    ): StartStreamResponseDto = mutex.withLock {
        if (!request.isValid()) {
            return@withLock startResponse(
                request.streamId,
                StartStreamStatusDto.INVALID_REQUEST,
                message = "Invalid or unsupported sender-control request",
            )
        }
        if (request.receiverId in rejectedReceivers) {
            return@withLock startResponse(
                request.streamId,
                StartStreamStatusDto.REJECTED,
                message = "Connection was rejected on the phone",
            )
        }

        val active = activeConnection
        if (active != null &&
            (active.receiverId != request.receiverId || active.streamId != request.streamId)
        ) {
            return@withLock startResponse(
                request.streamId,
                StartStreamStatusDto.BUSY,
                message = "Phone is already streaming for another stream generation",
            )
        }

        val authentication = pairings.authentication(
            request.receiverId,
            request.pairingToken,
            peerAddress,
        )
        if (authentication == PairingAuthentication.Unpaired) {
            if (active != null) {
                return@withLock startResponse(
                    request.streamId,
                    StartStreamStatusDto.REJECTED,
                    message = "Invalid pairing credentials",
                )
            }
            pendingConnection = PendingConnection(request, peerAddress)
            pendingFlow.value = PendingApproval(
                receiverId = request.receiverId,
                receiverName = request.receiverName,
                streamId = request.streamId,
            )
            return@withLock startResponse(request.streamId, StartStreamStatusDto.APPROVAL_REQUIRED)
        }

        if (active != null) {
            return@withLock accepted(authentication, request.receiverId, request.streamId)
        }
        if (!cameraPermissionChecker()) {
            return@withLock startResponse(
                request.streamId,
                StartStreamStatusDto.CAMERA_PERMISSION_REQUIRED,
                message = "Camera permission is required on the phone",
            )
        }

        val endpoint = ReceiverEndpoint(peerAddress, request.receiverControlPort)
        val configuredSettings = settings.state.value
        val started = controller.start(
            endpoint = endpoint,
            preference = configuredSettings.codecPreference,
            profile = configuredSettings.profile,
        )
        if (started.isFailure) {
            return@withLock startResponse(
                request.streamId,
                StartStreamStatusDto.INVALID_REQUEST,
                message = started.exceptionOrNull()?.message ?: "Could not start the camera stream",
            )
        }

        activeConnection = ActiveConnection(
            receiverId = request.receiverId,
            receiverName = request.receiverName,
            streamId = request.streamId,
            endpoint = endpoint,
        )
        activeReceiverNameFlow.value = request.receiverName
        pendingConnection = null
        pendingFlow.value = null
        accepted(authentication, request.receiverId, request.streamId)
    }

    suspend fun handleStopRequest(
        request: StopStreamRequestDto,
        peerAddress: String,
    ): StopStreamResponseDto = mutex.withLock {
        if (!request.isValid()) {
            return@withLock stopResponse(
                request.streamId,
                StopStreamStatusDto.INVALID_REQUEST,
                "Invalid or unsupported sender-control request",
            )
        }
        val authentication = pairings.authentication(
            request.receiverId,
            request.pairingToken,
            peerAddress,
        )
        if (authentication !is PairingAuthentication.Authenticated &&
            authentication !is PairingAuthentication.PendingTokenDelivery
        ) {
            return@withLock stopResponse(
                request.streamId,
                StopStreamStatusDto.REJECTED,
                "Invalid pairing credentials",
            )
        }

        val active = activeConnection
        if (active == null) {
            return@withLock if (lastStoppedStreamId == request.streamId) {
                stopResponse(request.streamId, StopStreamStatusDto.ALREADY_STOPPED)
            } else {
                stopResponse(request.streamId, StopStreamStatusDto.STALE_STREAM)
            }
        }
        if (active.receiverId != request.receiverId || active.streamId != request.streamId) {
            return@withLock stopResponse(request.streamId, StopStreamStatusDto.STALE_STREAM)
        }

        val stopped = controller.stop()
        lastStoppedStreamId = active.streamId
        activeConnection = null
        activeReceiverNameFlow.value = null
        pendingConnection = null
        pendingFlow.value = null
        if (stopped.isSuccess) {
            stopResponse(request.streamId, StopStreamStatusDto.STOPPED)
        } else {
            stopResponse(
                request.streamId,
                StopStreamStatusDto.STOPPED,
                stopped.exceptionOrNull()?.message,
            )
        }
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

    suspend fun stop(): Result<Unit> = mutex.withLock {
        val streamId = activeConnection?.streamId
        val stopped = controller.stop()
        if (streamId != null) lastStoppedStreamId = streamId
        activeConnection = null
        activeReceiverNameFlow.value = null
        stopped
    }

    suspend fun forgetPairing(): Result<Unit> = mutex.withLock {
        val active = activeConnection
        val stopped = controller.stop()
        if (stopped.isFailure) return@withLock stopped
        if (active != null) lastStoppedStreamId = active.streamId
        pairings.forgetAll()
        approvedReceiverFlow.value = false
        pendingConnection = null
        pendingFlow.value = null
        rejectedReceivers.clear()
        activeConnection = null
        activeReceiverNameFlow.value = null
        stopped
    }

    private fun accepted(
        authentication: PairingAuthentication,
        receiverId: String,
        streamId: String,
    ): StartStreamResponseDto {
        val pairingToken = when (authentication) {
            is PairingAuthentication.Authenticated -> null
            is PairingAuthentication.PendingTokenDelivery -> {
                pairings.markTokenDelivered(receiverId)
                authentication.token
            }
            PairingAuthentication.Unpaired -> null
        }
        return startResponse(streamId, StartStreamStatusDto.ACCEPTED, pairingToken = pairingToken)
    }

    private fun startResponse(
        streamId: String,
        status: StartStreamStatusDto,
        pairingToken: String? = null,
        message: String? = null,
    ) = StartStreamResponseDto(
        protocolVersion = SENDER_CONTROL_PROTOCOL_VERSION,
        action = SenderControlActionDto.START_RESULT,
        streamId = safeStreamId(streamId),
        senderId = pairings.senderId,
        status = status,
        pairingToken = pairingToken,
        message = message,
    )

    private fun stopResponse(
        streamId: String,
        status: StopStreamStatusDto,
        message: String? = null,
    ) = StopStreamResponseDto(
        protocolVersion = SENDER_CONTROL_PROTOCOL_VERSION,
        action = SenderControlActionDto.STOP_RESULT,
        streamId = safeStreamId(streamId),
        senderId = pairings.senderId,
        status = status,
        message = message,
    )

    private fun StartStreamRequestDto.isValid(): Boolean =
        protocolVersion == SENDER_CONTROL_PROTOCOL_VERSION &&
            action == SenderControlActionDto.START &&
            streamId.isValidStreamId() &&
            receiverId.isNotBlank() &&
            receiverName.isNotBlank() &&
            receiverControlPort in MIN_VALID_NETWORK_PORT..MAX_VALID_NETWORK_PORT

    private fun StopStreamRequestDto.isValid(): Boolean =
        protocolVersion == SENDER_CONTROL_PROTOCOL_VERSION &&
            action == SenderControlActionDto.STOP &&
            streamId.isValidStreamId() &&
            receiverId.isNotBlank() &&
            pairingToken.isNotBlank()

    private data class PendingConnection(
        val request: StartStreamRequestDto,
        val peerAddress: String,
    )

    private data class ActiveConnection(
        val receiverId: String,
        val receiverName: String,
        val streamId: String,
        val endpoint: ReceiverEndpoint,
    )

    private fun safeStreamId(streamId: String): String =
        streamId.takeIf(String::isValidStreamId) ?: ZERO_STREAM_ID

    private companion object {
        const val ZERO_STREAM_ID = "00000000-0000-0000-0000-000000000000"
    }
}
