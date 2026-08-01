package dev.mobilewebcam.sender.session

import android.view.Surface
import dev.mobilewebcam.sender.capabilities.EncoderCapabilityProbe
import dev.mobilewebcam.sender.control.ReceiverControlClient
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
import dev.mobilewebcam.sender.validation.ReceiverEndpointValidator
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

    override val state: StateFlow<StreamState> = stateFlow.asStateFlow()

    init {
        scope.launch {
            streamEngine.events.collectLatest(::handleEngineEvent)
        }
    }

    override suspend fun start(
        host: String,
        controlPort: Int,
        preference: CodecPreference,
        profile: VideoProfile,
        previewSurface: Surface?,
    ): Result<Unit> = lifecycleMutex.withLock {
        if (activeSession != null) {
            return@withLock failure(StreamFailure.ReceiverRejectedProfile("A stream is already active"))
        }
        logger.info("stream start requested", mapOf("host" to host, "controlPort" to controlPort))
        stateFlow.value = StreamState.CheckingReceiver
        try {
            val endpoint = ReceiverEndpointValidator.validate(host, controlPort)
                .getOrElse { throw StreamFailureException(StreamFailure.ReceiverUnavailable(it.message.orEmpty()), it) }
            receiver.health(endpoint).orReceiverFailure("Health check failed")
            val receiverCapabilities = receiver.capabilities(endpoint)
                .orReceiverFailure("Capability request failed")
            stateFlow.value = StreamState.Negotiating
            val senderCapabilities = SenderCapabilities(capabilityProbe.getCapabilities(listOf(profile)))
            val codec = negotiator.negotiate(preference, senderCapabilities, receiverCapabilities, profile)
            logger.info(
                "codec negotiated",
                mapOf("codec" to codec.protocolId, "profile" to profile.id, "preference" to preference),
            )
            val request = PrepareSessionRequest(
                preferredCodecs = preference.candidates(),
                profile = profile,
                bitrateByCodec = VideoCodec.entries.associateWith(profile::bitrateFor),
            )
            val session = receiver.prepareSession(endpoint, request)
                .orReceiverFailure("Receiver rejected session preparation")
            activeSession = session
            checkNegotiatedCodec(codec, session.selectedCodec, preference, profile)
            stateFlow.value = StreamState.Preparing(codec, profile)
            val configuration = StreamConfiguration(
                codec = codec,
                profile = session.profile,
                bitrateBps = session.bitrateBps,
                keyframeIntervalSeconds = session.profile.keyframeIntervalSeconds,
            )
            StreamConfigurationValidator.validate(configuration)
                .getOrElse { throw StreamFailureException(StreamFailure.ReceiverRejectedProfile(it.message.orEmpty()), it) }
            foreground.start().getOrElse { cause -> throw StreamFailureException(StreamFailure.Unexpected(cause), cause) }
            streamEngine.prepare(previewSurface, configuration).getOrThrow()
            stateFlow.value = StreamState.Starting(session)
            streamEngine.start(endpoint.host, session.mediaPort).getOrThrow()
            stateFlow.value = StreamState.Streaming(session, System.currentTimeMillis())
            logger.info(
                "stream started",
                mapOf("sessionId" to session.sessionId, "codec" to session.selectedCodec.protocolId),
            )
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            cleanupLocked(null)
            stateFlow.value = StreamState.Idle
            throw cancelled
        } catch (failure: StreamFailureException) {
            cleanupLocked(activeSession)
            stateFlow.value = StreamState.Failed(failure.failure)
            logger.warn("stream start failed", failure)
            Result.failure(failure)
        } catch (error: Throwable) {
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
        cleanupLocked(activeSession)
        stateFlow.value = StreamState.Idle
        logger.info("stream stopped")
        Result.success(Unit)
    }

    override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = lifecycleMutex.withLock {
        if (activeSession == null) {
            return@withLock Result.failure(IllegalStateException("No active stream"))
        }
        streamEngine.updateBitrate(bitrateBps)
    }

    private suspend fun handleEngineEvent(event: StreamEngineEvent) {
        lifecycleMutex.withLock {
            if (activeSession == null || stateFlow.value !is StreamState.Streaming) return@withLock
            val failure = when (event) {
                is StreamEngineEvent.ConnectionFailed ->
                    StreamFailure.StreamStartFailed(IllegalStateException(event.reason))
                StreamEngineEvent.Disconnected -> StreamFailure.NetworkDisconnected
                else -> return@withLock
            }
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
        val reason = when (error) {
            is ReceiverControlException -> error.error.toString()
            else -> error.message ?: "unknown receiver error"
        }
        throw StreamFailureException(StreamFailure.ReceiverUnavailable("$operation: $reason"), error)
    }

    private fun failure(failure: StreamFailure): Result<Unit> =
        Result.failure(StreamFailureException(failure))
}
