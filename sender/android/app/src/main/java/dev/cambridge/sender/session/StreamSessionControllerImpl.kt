package dev.cambridge.sender.session

import android.os.Build
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.logging.AndroidAppLogger
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.media.capabilities.EncoderCapabilityProbe
import dev.cambridge.sender.media.camera.CameraController
import dev.cambridge.sender.media.camera.CameraPermissionRequiredException
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
import dev.cambridge.sender.model.StreamSession
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.StreamVideoConfiguration
import dev.cambridge.sender.model.VideoCodec
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
    private var activeGeneration: Long? = null
    private var cleanupPending = false
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
        configuration: StreamVideoConfiguration,
    ): Result<Unit> = lifecycleMutex.withLock {
        val profile = configuration.profile
        val orientation = configuration.streamOrientation
        if (activeSession != null) {
            return@withLock failure(StreamFailure.StreamStartFailed(IllegalStateException("A stream is already active")))
        }
        val runId = "$RUN_ID_PREFIX${UUID.randomUUID()}"
        activeRunId = runId
        cleanupPending = true
        stateFlow.value = StreamState.Connecting
        val sessionTransform = runCatching {
            cameraController?.snapshotSessionTransform(profile.width, profile.height, orientation)
                ?: SessionTransform.forProfile(
                    displayOrientation = orientation.toDisplayOrientation(),
                    codedWidth = profile.width,
                    codedHeight = profile.height,
                )
        }.getOrElse { cause ->
            val failure = cause.toStreamFailure(StreamFailure.VideoQualityUnsupported(profile))
            diagnosticEvent(
                "stream_start_failed",
                mapOf("failureType" to failure::class.simpleName, "reason" to cause.message),
            )
            cleanupLocked()
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
            val encoderName = configuration.encoderName?.takeIf(String::isNotBlank)
                ?: throw StreamFailureException(
                    StreamFailure.EncoderPreparationFailed(
                        codec = VideoCodec.H264,
                        cause = IllegalArgumentException("No exact H.264 encoder is selected"),
                    ),
                )
            val encoderCapability = capabilityProbe.getCapabilities(listOf(profile))
                .firstOrNull { it.codec == VideoCodec.H264 && it.implementationName == encoderName }
            if (encoderCapability == null || !EncoderCatalog.hasCompleteMode(encoderCapability, profile)) {
                throw StreamFailureException(
                    StreamFailure.EncoderPreparationFailed(
                        codec = VideoCodec.H264,
                        cause = IllegalArgumentException(
                            "Selected encoder does not support the exact requested video mode",
                        ),
                    ),
                )
            }
            val cameraSupported = cameraController?.supportedVideoModes(listOf(profile))
                ?.contains(profile.id)
                ?: true
            if (!cameraSupported) {
                throw StreamFailureException(
                    StreamFailure.CameraUnavailable,
                    IllegalArgumentException("Camera2 no longer supports the selected video mode"),
                )
            }
            val encoderMode = encoderCapability.modeFor(profile.id)
                ?: error("Selected encoder mode disappeared during validation")
            val encoderMinimumBitrate = encoderMode.minimumBitrateBps
                ?: error("Selected encoder did not report a bitrate range")
            val encoderMaximumBitrate = encoderMode.maximumBitrateBps
                ?: error("Selected encoder did not report a bitrate range")
            if (profile.clampToStep(
                    configuration.bitrateBps,
                    encoderMinimumBitrate,
                    encoderMaximumBitrate,
                ) != configuration.bitrateBps
            ) {
                throw StreamFailureException(
                    StreamFailure.EncoderPreparationFailed(
                        codec = VideoCodec.H264,
                        cause = IllegalArgumentException(
                            "Selected bitrate is outside the exact encoder capability range",
                        ),
                    ),
                )
            }
            val mediaRtpPort = CamBridgeStreamContract.DEFAULT_MEDIA_RTP_PORT
            val mediaRtcpPort = CamBridgeStreamContract.DEFAULT_MEDIA_RTCP_PORT
            require(mediaRtpPort != mediaRtcpPort &&
                mediaRtpPort in CamBridgeStreamContract.MINIMUM_PORT..CamBridgeStreamContract.MAXIMUM_PORT &&
                mediaRtcpPort in CamBridgeStreamContract.MINIMUM_PORT..CamBridgeStreamContract.MAXIMUM_PORT
            ) {
                "The configured CamBridge media ports are invalid"
            }
            val session = StreamSession(
                sessionId = "$SESSION_ID_PREFIX${UUID.randomUUID()}",
                endpoint = endpoint,
                selectedCodec = VideoCodec.H264,
                encoderName = encoderName,
                profile = profile,
                bitrateBps = configuration.bitrateBps,
                mediaRtpPort = mediaRtpPort,
                mediaRtcpPort = mediaRtcpPort,
                outputPixelFormat = OutputPixelFormat.NV12,
                warnings = emptyList(),
                streamGeneration = nextStreamGeneration++,
                sessionTransform = sessionTransform,
            )
            activeSession = session
            activeGeneration = session.streamGeneration
            diagnosticEvent(
                "session_created",
                mapOf(
                    "mediaRtpPort" to session.mediaRtpPort,
                    "mediaRtcpPort" to session.mediaRtcpPort,
                    "codec" to session.selectedCodec.protocolId,
                    "encoder" to session.encoderName,
                    "bitrateBps" to session.bitrateBps,
                    "outputPixelFormat" to session.outputPixelFormat,
                ),
            )
            val configuration = StreamConfiguration(
                codec = VideoCodec.H264,
                encoderName = session.encoderName,
                profile = session.profile,
                bitrateBps = session.bitrateBps,
                keyframeIntervalSeconds = session.profile.keyframeIntervalSeconds,
                runId = runId,
                sessionId = session.sessionId,
                streamGeneration = session.streamGeneration,
                sessionTransform = sessionTransform,
            )
            StreamConfigurationValidator.validate(configuration)
                .getOrElse {
                    throw StreamFailureException(
                        StreamFailure.EncoderPreparationFailed(VideoCodec.H264, it),
                    )
                }
            foreground.start().getOrElse { cause -> throw StreamFailureException(StreamFailure.Unexpected(cause), cause) }
            streamEngine.prepare(configuration).getOrElse { cause ->
                throw StreamFailureException(
                    cause.toStreamFailure(
                        StreamFailure.EncoderPreparationFailed(VideoCodec.H264, cause),
                    ),
                    cause,
                )
            }
            diagnosticEvent(
                "encoder_prepared",
                mapOf(
                    "codec" to configuration.codec.protocolId,
                    "encoder" to configuration.encoderName,
                    "width" to configuration.profile.width,
                    "height" to configuration.profile.height,
                    "fps" to configuration.profile.fps,
                    "bitrateBps" to configuration.bitrateBps,
                ),
            )
            diagnosticEvent(
                "media_stream_starting",
                mapOf(
                    "mediaRtpPort" to session.mediaRtpPort,
                    "mediaRtcpPort" to session.mediaRtcpPort,
                ),
            )
            streamEngine.start(
                CamBridgeStreamEndpoint(
                    host = session.endpoint.host,
                    controlPort = session.endpoint.controlPort,
                    mediaRtpPort = session.mediaRtpPort,
                    mediaRtcpPort = session.mediaRtcpPort,
                    sessionId = session.sessionId,
                    generation = session.streamGeneration,
                ),
            ).getOrElse { cause ->
                throw StreamFailureException(
                    cause.toStreamFailure(StreamFailure.StreamStartFailed(cause)),
                    cause,
                )
            }
            stateFlow.value = StreamState.Streaming(session, System.currentTimeMillis())
            diagnosticEvent(
                "stream_started",
                mapOf(
                    "codec" to session.selectedCodec.protocolId,
                    "encoder" to session.encoderName,
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
        if (!cleanupPending && activeSession == null) {
            stateFlow.value = StreamState.Idle
            return@withLock Result.success(Unit)
        }
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
            val state = stateFlow.value
            if (activeSession == null || (state !is StreamState.Connecting && state !is StreamState.Streaming)) {
                return@withLock
            }
            if (event.generation != activeGeneration) return@withLock
            logEngineEvent(event)
            when (event) {
                is StreamEngineEvent.Disconnected -> failActiveSessionLocked(StreamFailure.NetworkDisconnected)
                is StreamEngineEvent.FatalFailure -> failActiveSessionLocked(event.failure)
                is StreamEngineEvent.ConnectionFailed -> failActiveSessionLocked(
                    StreamFailure.EncoderPreparationFailed(
                        codec = VideoCodec.H264,
                        cause = IllegalStateException(event.reason),
                    ),
                )
                else -> Unit
            }
        }
    }

    private suspend fun failActiveSessionLocked(failure: StreamFailure) {
        diagnosticEvent("stream_failed", mapOf("failureType" to failure::class.simpleName))
        cleanupLocked()
        stateFlow.value = StreamState.Failed(failure)
    }

    private suspend fun cleanupLocked() {
        if (!cleanupPending) return
        cleanupPending = false
        activeSession = null
        activeRunId = null
        activeGeneration = null
        runCatching { streamEngine.stop() }
        runCatching { streamEngine.release() }
        foreground.stop()
    }

    private fun logEngineEvent(event: StreamEngineEvent) {
        when (event) {
            is StreamEngineEvent.ConnectionStarted -> diagnosticEvent(
                "encoder_connection_started",
                mapOf("endpoint" to event.endpoint),
            )
            is StreamEngineEvent.Connected -> diagnosticEvent("encoder_connected")
            is StreamEngineEvent.ConnectionFailed -> {
                val now = System.currentTimeMillis()
                if (now - lastLoggedTransportErrorAtMillis >= TRANSPORT_ERROR_LOG_INTERVAL_MILLIS) {
                    lastLoggedTransportErrorAtMillis = now
                    diagnosticEvent("encoder_connection_failed", mapOf("reason" to event.reason))
                }
            }
            is StreamEngineEvent.FatalFailure -> diagnosticEvent(
                "stream_fatal_failure",
                mapOf("failureType" to event.failure::class.simpleName),
            )
            is StreamEngineEvent.Disconnected -> diagnosticEvent("encoder_disconnected")
            is StreamEngineEvent.AuthenticationError -> diagnosticEvent("encoder_authentication_error")
            is StreamEngineEvent.AuthenticationSucceeded -> diagnosticEvent("encoder_authentication_succeeded")
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

    private fun Throwable.toStreamFailure(fallback: StreamFailure): StreamFailure = when (this) {
        is CameraPermissionRequiredException -> StreamFailure.CameraPermissionDenied
        is StreamFailureException -> failure
        else -> fallback
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
