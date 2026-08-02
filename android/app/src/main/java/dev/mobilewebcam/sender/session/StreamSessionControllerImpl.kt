package dev.mobilewebcam.sender.session

import android.os.Build
import dev.mobilewebcam.sender.capabilities.EncoderCapabilityProbe
import dev.mobilewebcam.sender.control.ReceiverControlClient
import dev.mobilewebcam.sender.control.ReceiverControlError
import dev.mobilewebcam.sender.control.ReceiverControlException
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderCapabilities
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.platform.ForegroundStreamingController
import dev.mobilewebcam.sender.streaming.StreamEngine
import dev.mobilewebcam.sender.streaming.StreamEngineEvent
import dev.mobilewebcam.sender.validation.StreamConfigurationValidator
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
    private val receiver: ReceiverControlClient,
    private val capabilityProbe: EncoderCapabilityProbe,
    private val negotiator: CodecNegotiator,
    private val streamEngine: StreamEngine,
    private val foreground: ForegroundStreamingController,
    private val logger: AppLogger = AndroidAppLogger,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : StreamSessionController {
    private val lifecycleMutex = Mutex()
    private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
    private var activeSession: NegotiatedSession? = null
    private var activeRunId: String? = null
    private var lastLoggedBitrateAtMillis: Long = NO_BITRATE_SAMPLE_TIMESTAMP_MILLIS

    override val state: StateFlow<StreamState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            streamEngine.events.collectLatest(::handleEngineEvent)
        }
    }

    override suspend fun start(
        endpoint: ReceiverEndpoint,
        preference: CodecPreference,
        profile: VideoProfile,
    ): Result<Unit> = lifecycleMutex.withLock {
        if (activeSession != null) {
            return@withLock failure(StreamFailure.ReceiverRejectedProfile("A stream is already active"))
        }
        val runId = "$RUN_ID_PREFIX${UUID.randomUUID()}"
        activeRunId = runId
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
            ),
        )
        stateFlow.value = StreamState.CheckingReceiver
        try {
            receiver.health(endpoint).orReceiverFailure("Health check failed")
            diagnosticEvent("receiver_health_checked")
            val receiverCapabilities = receiver.capabilities(endpoint)
                .orReceiverFailure("Capability request failed")
            diagnosticEvent(
                "receiver_capabilities_received",
                mapOf("codecCount" to receiverCapabilities.codecs.size),
            )
            stateFlow.value = StreamState.Negotiating
            val senderCapabilities = SenderCapabilities(capabilityProbe.getCapabilities(listOf(profile)))
            val codec = negotiator.negotiate(preference, senderCapabilities, receiverCapabilities, profile)
            diagnosticEvent(
                "codec_negotiated",
                mapOf(
                    "codec" to codec.protocolId,
                    "profileId" to profile.id,
                    "width" to profile.width,
                    "height" to profile.height,
                    "fps" to profile.fps,
                    "targetBitrateBps" to profile.bitrateFor(codec),
                    "preference" to preference,
                ),
            )
            val request = PrepareSessionRequest(
                preferredCodecs = preference.candidates(),
                profile = profile,
                bitrateByCodec = VideoCodec.entries.associateWith(profile::bitrateFor),
            )
            val session = receiver.prepareSession(endpoint, request)
                .orPrepareFailure()
            activeSession = session
            diagnosticEvent(
                "receiver_session_prepared",
                mapOf(
                    "sessionId" to session.sessionId,
                    "mediaPort" to session.mediaPort,
                    "codec" to session.selectedCodec.protocolId,
                    "bitrateBps" to session.bitrateBps,
                    "outputPixelFormat" to session.outputPixelFormat,
                ),
            )
            checkNegotiatedCodec(codec, session.selectedCodec, preference, profile)
            stateFlow.value = StreamState.Preparing(codec, profile)
            val configuration = StreamConfiguration(
                codec = codec,
                profile = session.profile,
                bitrateBps = session.bitrateBps,
                keyframeIntervalSeconds = session.profile.keyframeIntervalSeconds,
                runId = runId,
                sessionId = session.sessionId,
            )
            StreamConfigurationValidator.validate(configuration)
                .getOrElse { throw StreamFailureException(StreamFailure.ReceiverRejectedProfile(it.message.orEmpty()), it) }
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
            stateFlow.value = StreamState.Starting(session)
            diagnosticEvent("media_stream_starting", mapOf("mediaPort" to session.mediaPort))
            streamEngine.start(endpoint.host, session.mediaPort).getOrThrow()
            stateFlow.value = StreamState.Streaming(session, System.currentTimeMillis())
            diagnosticEvent(
                "stream_started",
                mapOf("codec" to session.selectedCodec.protocolId, "bitrateBps" to session.bitrateBps),
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            cleanupLocked(activeSession)
            stateFlow.value = StreamState.Idle
            throw cancelled
        } catch (failure: StreamFailureException) {
            diagnosticEvent(
                "stream_start_failed",
                mapOf("failureType" to failure.failure::class.simpleName, "reason" to failure.message),
            )
            cleanupLocked(activeSession)
            stateFlow.value = StreamState.Failed(failure.failure)
            logger.warn("stream start failed", failure)
            Result.failure(failure)
        } catch (error: Throwable) {
            diagnosticEvent(
                "stream_failed",
                mapOf("failureType" to error::class.simpleName, "reason" to error.message),
            )
            cleanupLocked(activeSession)
            val failure = StreamFailure.Unexpected(error)
            stateFlow.value = StreamState.Failed(failure)
            logger.error("unexpected stream failure", error)
            Result.failure(StreamFailureException(failure, error))
        }
    }

    override suspend fun stop(): Result<Unit> = lifecycleMutex.withLock {
        if (activeSession == null && stateFlow.value == StreamState.Idle) return@withLock Result.success(Unit)
        stateFlow.value = StreamState.Stopping
        diagnosticEvent("stream_stopping")
        diagnosticEvent("stream_stopped")
        cleanupLocked(activeSession)
        stateFlow.value = StreamState.Idle
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
            val failure = when (event) {
                is StreamEngineEvent.ConnectionFailed ->
                    StreamFailure.StreamStartFailed(IllegalStateException(event.reason))
                StreamEngineEvent.Disconnected -> StreamFailure.NetworkDisconnected
                else -> return@withLock
            }
            diagnosticEvent("stream_failed", mapOf("failureType" to failure::class.simpleName))
            cleanupLocked(activeSession)
            stateFlow.value = StreamState.Failed(failure)
        }
    }

    private suspend fun cleanupLocked(session: NegotiatedSession?) {
        runCatching { streamEngine.stop() }
        runCatching { streamEngine.release() }
        foreground.stop()
        if (session != null) {
            runCatching { receiver.stopSession(session.endpoint, session.sessionId) }
        }
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
            is StreamEngineEvent.ConnectionFailed -> diagnosticEvent(
                "encoder_connection_failed",
                mapOf("reason" to event.reason),
            )
            StreamEngineEvent.Disconnected -> diagnosticEvent("encoder_disconnected")
            StreamEngineEvent.AuthenticationError -> diagnosticEvent("encoder_authentication_error")
            StreamEngineEvent.AuthenticationSucceeded -> diagnosticEvent("encoder_authentication_succeeded")
            is StreamEngineEvent.BitrateChanged -> {
                val now = System.currentTimeMillis()
                if (now - lastLoggedBitrateAtMillis >= BITRATE_SAMPLE_INTERVAL_MILLIS) {
                    lastLoggedBitrateAtMillis = now
                    diagnosticEvent(
                        "encoder_bitrate_changed",
                        mapOf("bitrateBps" to event.bitrateBps),
                    )
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

    private fun checkNegotiatedCodec(
        expected: VideoCodec,
        actual: VideoCodec,
        preference: CodecPreference,
        profile: VideoProfile,
    ) {
        if (expected == actual) return
        val failure = if (preference == CodecPreference.AUTO_PREFER_H265) {
            StreamFailure.ReceiverRejectedProfile("Receiver selected ${actual.protocolId}, expected ${expected.protocolId}")
        } else {
            StreamFailure.ForcedCodecUnsupported(expected, profile)
        }
        throw StreamFailureException(failure)
    }

    private fun CodecPreference.candidates(): List<VideoCodec> = when (this) {
        CodecPreference.AUTO_PREFER_H265 -> listOf(VideoCodec.H265, VideoCodec.H264)
        CodecPreference.FORCE_H264 -> listOf(VideoCodec.H264)
        CodecPreference.FORCE_H265 -> listOf(VideoCodec.H265)
    }

    private fun <T> Result<T>.orReceiverFailure(operation: String): T = getOrElse { error ->
        val reason = receiverErrorReason(error)
        throw StreamFailureException(StreamFailure.ReceiverUnavailable("$operation: $reason"), error)
    }

    private fun <T> Result<T>.orPrepareFailure(): T = getOrElse { error ->
        val failure = when (val controlError = (error as? ReceiverControlException)?.error) {
            is ReceiverControlError.Rejected -> StreamFailure.ReceiverRejectedProfile(controlError.reason)
            else -> StreamFailure.ReceiverUnavailable(
                "Receiver session preparation failed: ${receiverErrorReason(error)}",
            )
        }
        throw StreamFailureException(failure, error)
    }

    private fun receiverErrorReason(error: Throwable): String = when (error) {
        is ReceiverControlException -> error.error.toString()
        else -> error.message ?: "unknown receiver error"
    }

    private fun failure(failure: StreamFailure): Result<Unit> =
        Result.failure(StreamFailureException(failure))

    private companion object {
        const val RUN_ID_PREFIX = "run-"
        const val BITRATE_SAMPLE_INTERVAL_MILLIS = 5_000L
        const val NO_BITRATE_SAMPLE_TIMESTAMP_MILLIS = 0L
    }
}
