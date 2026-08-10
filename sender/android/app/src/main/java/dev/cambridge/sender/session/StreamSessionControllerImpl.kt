package dev.cambridge.sender.session

import android.os.Build
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.logging.AndroidAppLogger
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.media.capabilities.EncoderCapabilityProbe
import dev.cambridge.sender.media.camera.CameraController
import dev.cambridge.sender.media.camera.SessionTransform
import dev.cambridge.sender.media.camera.toDisplayOrientation
import dev.cambridge.sender.media.streaming.StreamEngine
import dev.cambridge.sender.media.streaming.StreamEngineEvent
import dev.cambridge.sender.model.CamBridgeStreamEndpoint
import dev.cambridge.sender.model.OutputPixelFormat
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.StreamConfiguration
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamFailureException
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.StreamSession
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.platform.service.ForegroundStreamingController
import kotlinx.coroutines.CancellationException
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
import java.util.UUID

class StreamSessionControllerImpl(
    private val capabilityProbe: EncoderCapabilityProbe,
    private val streamEngine: StreamEngine,
    private val foreground: ForegroundStreamingController,
    private val logger: AppLogger = AndroidAppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val cameraController: CameraController? = null,
) : StreamSessionController {
    private val lifecycleMutex = Mutex()
    private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
    private var activeSession: StreamSession? = null
    private var activeRunId: String? = null
    private var nextStreamGeneration = CamBridgeStreamContract.FIRST_STREAM_GENERATION
    private var lastLoggedBitrateAtMillis: Long = NO_SAMPLE_TIMESTAMP_MILLIS
    private var lastLoggedTransportErrorAtMillis: Long = NO_SAMPLE_TIMESTAMP_MILLIS

    override val state: StateFlow<StreamState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            streamEngine.events.collectLatest(::handleEngineEvent)
        }
    }

    override suspend fun start(
        endpoint: ReceiverEndpoint,
        profile: VideoProfile,
        orientation: StreamOrientation,
    ): Result<Unit> = start(endpoint, profile, orientation, profile.defaultBitrateBps)

    override suspend fun start(
        endpoint: ReceiverEndpoint,
        profile: VideoProfile,
        orientation: StreamOrientation,
        bitrateBps: Int,
    ): Result<Unit> = lifecycleMutex.withLock {
        if (activeSession != null) {
            return@withLock failure(StreamFailure.StreamStartFailed(IllegalStateException("A stream is already active")))
        }
        val runId = "$RUN_ID_PREFIX${UUID.randomUUID()}"
        activeRunId = runId
        stateFlow.value = StreamState.Connecting
        val sessionTransform = runCatching {
            cameraController?.snapshotSessionTransform(profile.width, profile.height, orientation)
                ?: SessionTransform.forProfile(
                    displayOrientation = orientation.toDisplayOrientation(),
                    codedWidth = profile.width,
                    codedHeight = profile.height,
                )
        }.getOrElse { cause ->
            val failure = StreamFailure.VideoQualityUnsupported(profile)
            diagnosticEvent(
                "stream_start_failed",
                mapOf("failureType" to failure::class.simpleName, "reason" to cause.message),
            )
            stateFlow.value = StreamState.Failed(failure)
            return@withLock Result.failure(StreamFailureException(failure, cause))
        }
        diagnosticEvent(
            "stream_start_requested",
            mapOf("host" to endpoint.host, "controlPort" to endpoint.controlPort),
        )
        diagnosticEvent(
            "sender_environment",
            mapOf(
                "phoneManufacturer" to Build.MANUFACTURER,
                "phoneModel" to Build.MODEL,
                "androidApi" to Build.VERSION.SDK_INT,
                "profileId" to profile.id,
                "width" to profile.width,
                "height" to profile.height,
                "fps" to profile.fps,
                "displayWidth" to sessionTransform.displayWidth,
                "displayHeight" to sessionTransform.displayHeight,
                "rotationDegrees" to sessionTransform.rotationDegrees,
            ),
        )
        try {
            val encoderCapability = capabilityProbe.getCapabilities(listOf(profile))
                .firstOrNull { it.codec == VideoCodec.H264 && it.profileId == profile.id }
            if (encoderCapability?.supported != true) {
                throw StreamFailureException(StreamFailure.VideoQualityUnsupported(profile))
            }
            val encoderMinimumBitrate = encoderCapability.minimumBitrateBps ?: profile.minimumBitrateBps
            val encoderMaximumBitrate = encoderCapability.maximumBitrateBps ?: profile.maximumBitrateBps
            if (profile.clampToStep(bitrateBps, encoderMinimumBitrate, encoderMaximumBitrate) != bitrateBps) {
                throw StreamFailureException(
                    StreamFailure.VideoQualityUnsupported(profile),
                    IllegalArgumentException("Selected bitrate is outside the phone encoder capability range"),
                )
            }
            val mediaPort = endpoint.controlPort + CamBridgeStreamContract.DEFAULT_MEDIA_PORT_OFFSET
            require(mediaPort in CamBridgeStreamContract.MINIMUM_PORT..CamBridgeStreamContract.MAXIMUM_PORT) {
                "The configured control port cannot derive a media port"
            }
            val session = StreamSession(
                sessionId = "$SESSION_ID_PREFIX${UUID.randomUUID()}",
                endpoint = endpoint,
                selectedCodec = VideoCodec.H264,
                profile = profile,
                bitrateBps = bitrateBps,
                mediaPort = mediaPort,
                outputPixelFormat = OutputPixelFormat.NV12,
                warnings = emptyList(),
                streamGeneration = nextStreamGeneration++,
                sessionTransform = sessionTransform,
            )
            activeSession = session
            diagnosticEvent(
                "session_created",
                mapOf(
                    "mediaPort" to session.mediaPort,
                    "codec" to session.selectedCodec.protocolId,
                    "bitrateBps" to session.bitrateBps,
                    "outputPixelFormat" to session.outputPixelFormat,
                ),
            )
            val configuration = StreamConfiguration(
                codec = VideoCodec.H264,
                profile = session.profile,
                bitrateBps = session.bitrateBps,
                keyframeIntervalSeconds = session.profile.keyframeIntervalSeconds,
                runId = runId,
                sessionId = session.sessionId,
                sessionTransform = sessionTransform,
            )
            StreamConfigurationValidator.validate(configuration)
                .getOrElse { throw StreamFailureException(StreamFailure.VideoQualityUnsupported(profile), it) }
            foreground.start().getOrElse { cause -> throw StreamFailureException(StreamFailure.Unexpected(cause), cause) }
            streamEngine.prepare(configuration).getOrThrow()
            diagnosticEvent(
                "encoder_prepared",
                mapOf(
                    "codec" to configuration.codec.protocolId,
                    "width" to configuration.profile.width,
                    "height" to configuration.profile.height,
                    "fps" to configuration.profile.fps,
                    "bitrateBps" to configuration.bitrateBps,
                ),
            )
            diagnosticEvent("media_stream_starting", mapOf("mediaPort" to session.mediaPort))
            streamEngine.start(
                CamBridgeStreamEndpoint(
                    host = session.endpoint.host,
                    controlPort = session.endpoint.controlPort,
                    mediaPort = session.mediaPort,
                    sessionId = session.sessionId,
                    generation = session.streamGeneration,
                ),
            ).getOrElse { cause ->
                throw StreamFailureException(StreamFailure.StreamStartFailed(cause), cause)
            }
            stateFlow.value = StreamState.Streaming(session, System.currentTimeMillis())
            diagnosticEvent(
                "stream_started",
                mapOf(
                    "codec" to session.selectedCodec.protocolId,
                    "bitrateBps" to session.bitrateBps,
                ),
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            cleanupLocked()
            stateFlow.value = StreamState.Idle
            throw cancelled
        } catch (failure: StreamFailureException) {
            diagnosticEvent(
                "stream_start_failed",
                mapOf("failureType" to failure.failure::class.simpleName, "reason" to failure.message),
            )
            cleanupLocked()
            stateFlow.value = StreamState.Failed(failure.failure)
            logger.warn("stream start failed", failure)
            Result.failure(failure)
        } catch (error: Throwable) {
            diagnosticEvent(
                "stream_failed",
                mapOf("failureType" to error::class.simpleName, "reason" to error.message),
            )
            cleanupLocked()
            val failure = StreamFailure.StreamStartFailed(error)
            stateFlow.value = StreamState.Failed(failure)
            logger.error("stream start failed", error)
            Result.failure(StreamFailureException(failure, error))
        }
    }

    override suspend fun stop(): Result<Unit> = lifecycleMutex.withLock {
        if (activeSession == null && stateFlow.value == StreamState.Idle) return@withLock Result.success(Unit)
        stateFlow.value = StreamState.Stopping
        diagnosticEvent("stream_stopping")
        cleanupLocked()
        stateFlow.value = StreamState.Idle
        diagnosticEvent("stream_stopped")
        Result.success(Unit)
    }

    override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = lifecycleMutex.withLock {
        if (activeSession == null) {
            return@withLock Result.failure(IllegalStateException("No active stream"))
        }
        diagnosticEvent("bitrate_update_requested", mapOf("bitrateBps" to bitrateBps))
        return@withLock streamEngine.updateBitrate(bitrateBps).also { result ->
            if (result.isSuccess) {
                diagnosticEvent("bitrate_updated", mapOf("bitrateBps" to bitrateBps))
            }
        }
    }

    private suspend fun handleEngineEvent(event: StreamEngineEvent) {
        lifecycleMutex.withLock {
            if (activeSession == null || stateFlow.value !is StreamState.Streaming) return@withLock
            logEngineEvent(event)
            if (event == StreamEngineEvent.Disconnected) {
                diagnosticEvent("stream_failed", mapOf("failureType" to "NetworkDisconnected"))
                cleanupLocked()
                stateFlow.value = StreamState.Failed(StreamFailure.NetworkDisconnected)
            }
        }
    }

    private suspend fun cleanupLocked() {
        runCatching { streamEngine.stop() }
        runCatching { streamEngine.release() }
        foreground.stop()
        activeSession = null
        activeRunId = null
    }

    private fun logEngineEvent(event: StreamEngineEvent) {
        when (event) {
            is StreamEngineEvent.ConnectionStarted -> diagnosticEvent(
                "encoder_connection_started",
                mapOf("endpoint" to event.endpoint),
            )
            StreamEngineEvent.Connected -> diagnosticEvent("encoder_connected")
            is StreamEngineEvent.ConnectionFailed -> {
                val now = System.currentTimeMillis()
                if (now - lastLoggedTransportErrorAtMillis >= TRANSPORT_ERROR_LOG_INTERVAL_MILLIS) {
                    lastLoggedTransportErrorAtMillis = now
                    diagnosticEvent("encoder_connection_failed", mapOf("reason" to event.reason))
                }
            }
            StreamEngineEvent.Disconnected -> diagnosticEvent("encoder_disconnected")
            StreamEngineEvent.AuthenticationError -> diagnosticEvent("encoder_authentication_error")
            StreamEngineEvent.AuthenticationSucceeded -> diagnosticEvent("encoder_authentication_succeeded")
            is StreamEngineEvent.BitrateChanged -> {
                val now = System.currentTimeMillis()
                if (now - lastLoggedBitrateAtMillis >= BITRATE_SAMPLE_INTERVAL_MILLIS) {
                    lastLoggedBitrateAtMillis = now
                    diagnosticEvent("encoder_bitrate_changed", mapOf("bitrateBps" to event.bitrateBps))
                }
            }
        }
    }

    private fun diagnosticEvent(name: String, fields: Map<String, Any?> = emptyMap()) {
        val context = mapOf(
            "runId" to activeRunId,
            "sessionId" to activeSession?.sessionId,
        )
        logger.event(name, (fields + context).filterValues { it != null })
    }

    private fun failure(failure: StreamFailure): Result<Unit> =
        Result.failure(StreamFailureException(failure))

    private companion object {
        const val RUN_ID_PREFIX = "run-"
        const val SESSION_ID_PREFIX = "session-"
        const val BITRATE_SAMPLE_INTERVAL_MILLIS = 5_000L
        const val TRANSPORT_ERROR_LOG_INTERVAL_MILLIS = 5_000L
        const val NO_SAMPLE_TIMESTAMP_MILLIS = 0L
    }
}
