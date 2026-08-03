package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.session.VideoProfiles
import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.DecoderAcceleration
import dev.mobilewebcam.sender.model.EncoderAcceleration
import dev.mobilewebcam.sender.model.EncoderCapability
import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverCodecCapability
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverHealth
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.platform.service.ForegroundStreamingController
import dev.mobilewebcam.sender.session.CodecNegotiator
import dev.mobilewebcam.sender.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.media.streaming.StreamEngine
import dev.mobilewebcam.sender.media.streaming.StreamEngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionControllerTest {
    @Test
    fun startAndStopAreIdempotentAndDoNotDuplicatePreparation() = runTest {
        val receiver = FakeReceiver()
        val engine = FakeEngine()
        val foreground = FakeForeground()
        val controller = controller(receiver, engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isSuccess)
        assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isFailure)
        assertEquals(1, engine.prepareCount)
        assertEquals(listOf(VideoCodec.H265), receiver.lastPreferredCodecs)

        assertTrue(controller.stop().isSuccess)
        assertTrue(controller.stop().isSuccess)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
        assertEquals(1, receiver.stopCount)
    }

    @Test
    fun automaticFallbackRequestsOnlyTheSenderSupportedCodec() = runTest {
        val receiver = FakeReceiver()
        val controller = StreamSessionControllerImpl(
            receiver = receiver,
            capabilityProbe = FakeProbe(h265Supported = false),
            negotiator = CodecNegotiator(),
            streamEngine = FakeEngine(),
            foreground = FakeForeground(),
            logger = TestLogger,
            scope = backgroundScope,
        )

        assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isSuccess)
        assertEquals(listOf(VideoCodec.H264), receiver.lastPreferredCodecs)
    }

    @Test
    fun encoderPreparationFailureStopsPreparedReceiverSession() = runTest {
        val receiver = FakeReceiver()
        val engine = FakeEngine(prepareFailure = IllegalStateException("test"))
        val controller = controller(receiver, engine, FakeForeground(), backgroundScope)

        assertTrue(controller.start(endpoint, CodecPreference.FORCE_H264, profile).isFailure)
        assertEquals(1, receiver.stopCount)
    }

    @Test
    fun connectionFailureDuringStreamingKeepsTheSessionAlive() = runTest {
        val receiver = FakeReceiver()
        val engine = FakeEngineWithEvents()
        val foreground = FakeForeground()
        val scope = eventScope(testScheduler)
        val controller = controller(receiver, engine, foreground, scope)

        try {
            assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isSuccess)
            testScheduler.runCurrent()
            engine.emit(StreamEngineEvent.ConnectionFailed("sendto failed: ENETUNREACH"))
            testScheduler.runCurrent()

            assertTrue(controller.state.value is StreamState.Streaming)
            assertEquals(0, receiver.stopCount)
            assertEquals(0, foreground.stopCount)
            assertEquals(0, engine.stopCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun explicitDisconnectDuringStreamingFailsTheSession() = runTest {
        val receiver = FakeReceiver()
        val engine = FakeEngineWithEvents()
        val foreground = FakeForeground()
        val scope = eventScope(testScheduler)
        val controller = controller(receiver, engine, foreground, scope)

        try {
            assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isSuccess)
            testScheduler.runCurrent()
            engine.emit(StreamEngineEvent.Disconnected)
            testScheduler.runCurrent()

            assertEquals(StreamState.Failed(StreamFailure.NetworkDisconnected), controller.state.value)
            assertEquals(1, receiver.stopCount)
            assertEquals(1, foreground.stopCount)
            assertEquals(1, engine.stopCount)
        } finally {
            scope.cancel()
        }
    }

    private fun eventScope(testScheduler: TestCoroutineScheduler) =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun receiverWatchdogIgnoresOneTransientFailure() = runTest {
        val receiver = FakeReceiver().apply { sessionStateFailuresRemaining = 1 }
        val controller = controller(receiver, FakeEngine(), FakeForeground(), backgroundScope)

        assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isSuccess)
        testScheduler.advanceTimeBy(WATCHDOG_INTERVAL_MILLIS)
        testScheduler.runCurrent()

        assertTrue(controller.state.value is StreamState.Streaming)
        assertEquals(1, receiver.sessionStateChecks)
        assertEquals(0, receiver.stopCount)
    }

    @Test
    fun receiverWatchdogStopsAfterConsecutiveFailures() = runTest {
        val receiver = FakeReceiver().apply { sessionStateFailuresRemaining = WATCHDOG_FAILURE_THRESHOLD }
        val engine = FakeEngine()
        val foreground = FakeForeground()
        val controller = controller(receiver, engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isSuccess)
        testScheduler.advanceTimeBy(WATCHDOG_INTERVAL_MILLIS * WATCHDOG_FAILURE_THRESHOLD)
        testScheduler.runCurrent()

        assertTrue(controller.state.value is StreamState.Failed)
        assertEquals(1, receiver.stopCount)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun receiverWatchdogIsCancelledByNormalStop() = runTest {
        val receiver = FakeReceiver()
        val controller = controller(receiver, FakeEngine(), FakeForeground(), backgroundScope)

        assertTrue(controller.start(endpoint, CodecPreference.AUTO_PREFER_H265, profile).isSuccess)
        assertTrue(controller.stop().isSuccess)
        testScheduler.advanceTimeBy(WATCHDOG_INTERVAL_MILLIS * WATCHDOG_FAILURE_THRESHOLD)
        testScheduler.runCurrent()

        assertEquals(0, receiver.sessionStateChecks)
    }

    private fun controller(
        receiver: FakeReceiver,
        engine: StreamEngine,
        foreground: FakeForeground,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = StreamSessionControllerImpl(
        receiver = receiver,
        capabilityProbe = FakeProbe(),
        negotiator = CodecNegotiator(),
        streamEngine = engine,
        foreground = foreground,
        logger = TestLogger,
        scope = scope,
    )

    private class FakeProbe(
        private val h265Supported: Boolean = true,
    ) : dev.mobilewebcam.sender.media.capabilities.EncoderCapabilityProbe {
        override suspend fun getCapabilities(profiles: List<VideoProfile>): List<EncoderCapability> =
            profiles.flatMap { profile ->
                VideoCodec.entries.map { codec ->
                    EncoderCapability(
                        codec = codec,
                        profileId = profile.id,
                        supported = codec != VideoCodec.H265 || h265Supported,
                        acceleration = EncoderAcceleration.HARDWARE,
                        encoderName = "fake-$codec",
                    )
                }
            }
    }

    private class FakeReceiver : ReceiverControlClient {
        var stopCount = 0
        var sessionStateChecks = 0
        var sessionStateFailuresRemaining = 0
        var lastPreferredCodecs: List<VideoCodec> = emptyList()
        override suspend fun health(endpoint: ReceiverEndpoint): Result<ReceiverHealth> =
            Result.success(ReceiverHealth("ready", 1))

        override suspend fun capabilities(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> =
            Result.success(receiverCapabilities())

        override suspend fun prepareSession(
            endpoint: ReceiverEndpoint,
            request: PrepareSessionRequest,
        ): Result<NegotiatedSession> {
            lastPreferredCodecs = request.preferredCodecs
            val codec = request.preferredCodecs.first()
            return Result.success(
                NegotiatedSession(
                    sessionId = "test-session",
                    endpoint = endpoint,
                    selectedCodec = codec,
                    profile = request.profile,
                    bitrateBps = request.bitrateByCodec.getValue(codec),
                    mediaPort = 5000,
                    outputPixelFormat = OutputPixelFormat.YUY2,
                    warnings = emptyList(),
                ),
            )
        }

        override suspend fun stopSession(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit> {
            stopCount += 1
            return Result.success(Unit)
        }

        override suspend fun sessionState(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit> =
            if (sessionStateFailuresRemaining > 0) {
                sessionStateFailuresRemaining -= 1
                sessionStateChecks += 1
                Result.failure(IllegalStateException("receiver unavailable"))
            } else {
                sessionStateChecks += 1
                Result.success(Unit)
            }
    }

    private class FakeEngine(
        private val prepareFailure: Throwable? = null,
    ) : StreamEngine {
        var prepareCount = 0
        var stopCount = 0
        override val events: Flow<StreamEngineEvent> = emptyFlow()

        override suspend fun prepare(
            configuration: StreamConfiguration,
        ): Result<Unit> {
            prepareCount += 1
            return prepareFailure?.let { Result.failure(it) } ?: Result.success(Unit)
        }

        override suspend fun start(receiverHost: String, mediaPort: Int): Result<Unit> = Result.success(Unit)

        override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = Result.success(Unit)

        override suspend fun stop(): Result<Unit> {
            stopCount += 1
            return Result.success(Unit)
        }

        override suspend fun release() = Unit
    }

    private class FakeEngineWithEvents : StreamEngine {
        var stopCount = 0
        private val eventFlow = MutableSharedFlow<StreamEngineEvent>()
        override val events: Flow<StreamEngineEvent> = eventFlow

        suspend fun emit(event: StreamEngineEvent) {
            eventFlow.emit(event)
        }

        override suspend fun prepare(
            configuration: StreamConfiguration,
        ): Result<Unit> = Result.success(Unit)

        override suspend fun start(receiverHost: String, mediaPort: Int): Result<Unit> = Result.success(Unit)

        override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = Result.success(Unit)

        override suspend fun stop(): Result<Unit> {
            stopCount += 1
            return Result.success(Unit)
        }

        override suspend fun release() = Unit
    }

    private class FakeForeground : ForegroundStreamingController {
        var stopCount = 0
        override fun start(): Result<Unit> = Result.success(Unit)
        override fun stop() {
            stopCount += 1
        }
    }

    private object TestLogger : AppLogger {
        override fun debug(message: String, fields: Map<String, Any?>) = Unit

        override fun info(message: String, fields: Map<String, Any?>) = Unit

        override fun warn(message: String, cause: Throwable?, fields: Map<String, Any?>) = Unit

        override fun error(message: String, cause: Throwable?, fields: Map<String, Any?>) = Unit
    }

    private companion object {
        val profile = VideoProfiles.default
        val endpoint = ReceiverEndpoint("127.0.0.1", 5001)
        const val WATCHDOG_INTERVAL_MILLIS = 2_000L
        const val WATCHDOG_FAILURE_THRESHOLD = 3

        fun receiverCapabilities() = ReceiverCapabilities(
            protocolVersion = 1,
            codecs = listOf(
                ReceiverCodecCapability(VideoCodec.H264, true, DecoderAcceleration.UNKNOWN),
                ReceiverCodecCapability(VideoCodec.H265, true, DecoderAcceleration.UNKNOWN),
            ),
            outputDevice = "/dev/video10",
            pixelFormats = listOf(OutputPixelFormat.YUY2),
            activeSession = false,
        )
    }
}
