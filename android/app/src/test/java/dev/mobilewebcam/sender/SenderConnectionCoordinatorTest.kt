package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.control.ReceiverControlClient
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.DecoderAcceleration
import dev.mobilewebcam.sender.model.NegotiatedSession
import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.PrepareSessionRequest
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverCodecCapability
import dev.mobilewebcam.sender.model.ReceiverDemand
import dev.mobilewebcam.sender.model.ReceiverDemandEvent
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverHealth
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.SrtTransportEndpoint
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.session.VideoProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SenderConnectionCoordinatorTest {
    @Test
    fun connectingLeavesTheSenderInStandbyWithoutStartingMedia() = runTest {
        val harness = harness(backgroundScope, testScheduler)

        assertTrue(harness.coordinator.connectToReceiver(harness.endpoint).isSuccess)
        testScheduler.runCurrent()

        assertEquals(StreamState.ConnectedStandby, harness.coordinator.streamState.value)
        assertEquals(0, harness.controller.startCount)
    }

    @Test
    fun firstDemandStartsOnceAndFinalReleaseReturnsToStandby() = runTest {
        val harness = harness(backgroundScope, testScheduler)
        connect(harness)

        harness.emit(activeDemand(generation = FIRST_GENERATION))
        assertEquals(1, harness.controller.startCount)

        harness.emit(activeDemand(generation = FIRST_GENERATION, consumerCount = SECOND_CONSUMER_COUNT))
        assertEquals(1, harness.controller.startCount)

        harness.emit(inactiveDemand(generation = FIRST_GENERATION))
        assertEquals(1, harness.controller.stopCount)
        assertEquals(StreamState.ConnectedStandby, harness.coordinator.streamState.value)

        harness.emit(inactiveDemand(generation = FIRST_GENERATION))
        assertEquals(1, harness.controller.stopCount)
    }

    @Test
    fun staleGenerationsCannotRestartOrStopTheCurrentMediaSession() = runTest {
        val harness = harness(backgroundScope, testScheduler)
        connect(harness)

        harness.emit(activeDemand(generation = FIRST_GENERATION))
        harness.emit(inactiveDemand(generation = FIRST_GENERATION))
        harness.emit(activeDemand(generation = SECOND_GENERATION))
        testScheduler.advanceTimeBy(RESTART_SETTLE_TIME_MILLIS)
        testScheduler.runCurrent()
        assertEquals(2, harness.controller.startCount)

        harness.emit(activeDemand(generation = FIRST_GENERATION, consumerCount = SECOND_CONSUMER_COUNT))
        harness.emit(inactiveDemand(generation = FIRST_GENERATION))

        assertEquals(2, harness.controller.startCount)
        assertEquals(1, harness.controller.stopCount)
        assertTrue(harness.coordinator.streamState.value is StreamState.Streaming)
    }

    @Test
    fun losingTheDemandConnectionStopsMediaAndReportsReceiverFailure() = runTest {
        val receiver = FakeReceiver().apply {
            demandFlow = flow {
                emit(activeDemand(generation = FIRST_GENERATION))
                throw IOException("receiver unavailable")
            }
        }
        val harness = harness(backgroundScope, testScheduler, receiver)

        connect(harness)

        assertEquals(1, harness.controller.startCount)
        assertEquals(1, harness.controller.stopCount)
        assertEquals(
            StreamState.Failed(StreamFailure.ReceiverUnavailable("Receiver demand connection was lost")),
            harness.coordinator.streamState.value,
        )
    }

    @Test
    fun stoppingDisconnectsTheDemandSubscriptionBeforeTheNextEvent() = runTest {
        val harness = harness(backgroundScope, testScheduler)
        connect(harness)
        harness.emit(activeDemand(generation = FIRST_GENERATION))

        assertTrue(harness.coordinator.stop().isSuccess)
        harness.emit(activeDemand(generation = SECOND_GENERATION))

        assertEquals(StreamState.Idle, harness.coordinator.streamState.value)
        assertEquals(1, harness.controller.startCount)
        assertEquals(1, harness.controller.stopCount)
    }

    private suspend fun connect(harness: TestHarness) {
        assertTrue(harness.coordinator.connectToReceiver(harness.endpoint).isSuccess)
        harness.testScheduler.runCurrent()
    }

    private suspend fun TestHarness.emit(event: ReceiverDemandEvent) {
        receiver.demandEvents.emit(event)
        testScheduler.runCurrent()
    }

    private fun harness(
        scope: CoroutineScope,
        testScheduler: TestCoroutineScheduler,
        receiver: FakeReceiver = FakeReceiver(),
    ): TestHarness {
        val controller = FakeController()
        val settings = FakeSettings()
        val endpoint = ReceiverEndpoint(
            host = "127.0.0.1",
            controlPort = CONTROL_PORT,
            displayName = "Test receiver",
        )
        return TestHarness(
            coordinator = SenderConnectionCoordinator(
                controller = controller,
                receiver = receiver,
                settings = settings,
                logger = TestLogger,
                scope = scope,
            ),
            controller = controller,
            receiver = receiver,
            endpoint = endpoint,
            testScheduler = testScheduler,
        )
    }

    private data class TestHarness(
        val coordinator: SenderConnectionCoordinator,
        val controller: FakeController,
        val receiver: FakeReceiver,
        val endpoint: ReceiverEndpoint,
        val testScheduler: TestCoroutineScheduler,
    )

    private class FakeController : StreamSessionController {
        private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
        override val state: StateFlow<StreamState> = stateFlow.asStateFlow()

        var startCount = 0
        var stopCount = 0

        override suspend fun start(
            endpoint: ReceiverEndpoint,
            preference: CodecPreference,
            profile: VideoProfile,
        ): Result<Unit> {
            startCount += 1
            stateFlow.value = StreamState.Streaming(testSession(endpoint), STREAM_START_TIME)
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<Unit> {
            stopCount += 1
            stateFlow.value = StreamState.Idle
            return Result.success(Unit)
        }

        override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = Result.success(Unit)
    }

    private class FakeReceiver : ReceiverControlClient {
        val demandEvents = MutableSharedFlow<ReceiverDemandEvent>(extraBufferCapacity = DEMAND_BUFFER_CAPACITY)
        var demandFlow: Flow<ReceiverDemandEvent> = demandEvents

        override suspend fun healthV2(endpoint: ReceiverEndpoint): Result<ReceiverHealth> =
            Result.success(ReceiverHealth("ready", CONTROL_V2_PROTOCOL_VERSION))

        override suspend fun capabilitiesV2(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> =
            Result.success(
                ReceiverCapabilities(
                    protocolVersion = CONTROL_V2_PROTOCOL_VERSION,
                    codecs = listOf(
                        ReceiverCodecCapability(VideoCodec.H264, true, DecoderAcceleration.UNKNOWN),
                    ),
                    outputDevice = "/dev/video10",
                    pixelFormats = listOf(OutputPixelFormat.YUY2),
                    activeSession = false,
                ),
            )

        override fun demandEventsV2(endpoint: ReceiverEndpoint): Flow<ReceiverDemandEvent> = demandFlow

        override suspend fun createSessionV2(
            endpoint: ReceiverEndpoint,
            request: PrepareSessionRequest,
        ): Result<NegotiatedSession> = Result.failure(UnsupportedOperationException("not used"))

        override suspend fun stopSessionV2(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit> =
            Result.success(Unit)

        override suspend fun sessionStateV2(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit> =
            Result.success(Unit)
    }

    private class FakeSettings : SenderSettingsRepository {
        private val stateFlow = MutableStateFlow(
            SenderSettings(
                codecPreference = CodecPreference.AUTO_PREFER_H265,
                profile = VideoProfiles.default,
            ),
        )
        override val state: StateFlow<SenderSettings> = stateFlow.asStateFlow()

        override fun updateCodecPreference(preference: CodecPreference) {
            stateFlow.value = stateFlow.value.copy(codecPreference = preference)
        }

        override fun updateProfile(profile: dev.mobilewebcam.sender.model.VideoProfile) {
            stateFlow.value = stateFlow.value.copy(profile = profile)
        }

        override fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) {
            stateFlow.value = stateFlow.value.copy(receiverEndpoint = endpoint)
        }
    }

    private object TestLogger : AppLogger {
        override fun debug(message: String, fields: Map<String, Any?>) = Unit
        override fun info(message: String, fields: Map<String, Any?>) = Unit
        override fun warn(message: String, cause: Throwable?, fields: Map<String, Any?>) = Unit
        override fun error(message: String, cause: Throwable?, fields: Map<String, Any?>) = Unit
    }

    private companion object {
        const val CONTROL_V2_PROTOCOL_VERSION = 2
        const val CONTROL_PORT = 5_001
        const val FIRST_GENERATION = 1L
        const val SECOND_GENERATION = 2L
        const val FIRST_CONSUMER_COUNT = 1
        const val SECOND_CONSUMER_COUNT = 2
        const val DEMAND_BUFFER_CAPACITY = 1
        const val STREAM_START_TIME = 100L
        const val RESTART_SETTLE_TIME_MILLIS = 2_000L

        fun activeDemand(generation: Long, consumerCount: Int = FIRST_CONSUMER_COUNT) =
            ReceiverDemandEvent(generation, ReceiverDemand.ACTIVE, consumerCount)

        fun inactiveDemand(generation: Long) =
            ReceiverDemandEvent(generation, ReceiverDemand.INACTIVE, 0)

        fun testSession(endpoint: ReceiverEndpoint) = NegotiatedSession(
            sessionId = "test-session",
            endpoint = endpoint,
            selectedCodec = VideoCodec.H264,
            profile = VideoProfiles.default,
            bitrateBps = VideoProfiles.default.h264BitrateBps,
            mediaPort = 5_000,
            outputPixelFormat = OutputPixelFormat.YUY2,
            warnings = emptyList(),
            srtEndpoint = SrtTransportEndpoint(
                host = endpoint.host,
                port = 5_000,
                streamId = "test-stream",
                latencyMs = 120,
                keyLengthBytes = 32,
                passphrase = "test-passphrase-0123456789",
            ),
        )
    }
}
