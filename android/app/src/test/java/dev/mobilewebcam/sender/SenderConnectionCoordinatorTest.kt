package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.NetworkChangeMonitor
import dev.mobilewebcam.sender.connection.ReconnectPolicy
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.model.StreamSession
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.session.VideoProfiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SenderConnectionCoordinatorTest {
    @Test
    fun connectStartsStreamingImmediately() = runTest {
        val controller = FakeController()
        val coordinator = coordinator(controller, backgroundScope)

        assertTrue(coordinator.connectToReceiver(endpoint).isSuccess)

        assertEquals(1, controller.startCount)
        assertEquals(listOf(endpoint), controller.attemptedEndpoints)
        assertTrue(coordinator.streamState.value is StreamState.Streaming)
        assertTrue(coordinator.hasConfiguredReceiver.value)
    }

    @Test
    fun failedConnectRetriesTheSameEndpoint() = runTest {
        val controller = FakeController(failuresBeforeSuccess = 1)
        val coordinator = coordinator(controller, backgroundScope, testReconnectPolicy())

        assertTrue(coordinator.connectToReceiver(endpoint).isFailure)
        testScheduler.runCurrent()
        assertEquals(StreamState.Reconnecting, coordinator.streamState.value)

        testScheduler.advanceTimeBy(RETRY_DELAY_MILLIS)
        testScheduler.runCurrent()

        assertEquals(listOf(endpoint, endpoint), controller.attemptedEndpoints)
        assertTrue(coordinator.streamState.value is StreamState.Streaming)
    }

    @Test
    fun stopCancelsReconnectAndPreventsLaterCameraActivation() = runTest {
        val controller = FakeController(alwaysUnavailable = true)
        val coordinator = coordinator(
            controller = controller,
            scope = backgroundScope,
            reconnectPolicy = testReconnectPolicy(maximumDelayMillis = LONG_RETRY_DELAY_MILLIS),
        )

        assertTrue(coordinator.connectToReceiver(endpoint).isFailure)
        testScheduler.runCurrent()
        val attemptsBeforeStop = controller.attemptedEndpoints.size

        assertTrue(coordinator.stop().isSuccess)
        testScheduler.advanceTimeBy(LONG_RETRY_DELAY_MILLIS * RETRY_ATTEMPT_COUNT)
        testScheduler.runCurrent()

        assertEquals(StreamState.Idle, coordinator.streamState.value)
        assertEquals(attemptsBeforeStop, controller.attemptedEndpoints.size)
    }

    @Test
    fun networkChangeRestartsTheSameConfiguredEndpoint() = runTest {
        val controller = FakeController()
        val monitor = FakeNetworkChangeMonitor()
        val coordinator = coordinator(controller, backgroundScope, monitor = monitor)

        assertTrue(coordinator.connectToReceiver(endpoint).isSuccess)
        testScheduler.runCurrent()
        monitor.emitChange()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(RETRY_DELAY_MILLIS)
        testScheduler.runCurrent()

        assertEquals(listOf(endpoint, endpoint), controller.attemptedEndpoints)
        assertTrue(coordinator.streamState.value is StreamState.Streaming)
    }

    @Test
    fun forgettingTheDesktopStopsMediaAndClearsTheEndpoint() = runTest {
        val controller = FakeController()
        val settings = FakeSettings()
        val coordinator = coordinator(controller, backgroundScope, settings = settings)

        assertTrue(coordinator.connectToReceiver(endpoint).isSuccess)
        assertTrue(coordinator.forgetReceiver().isSuccess)

        assertEquals(StreamState.Idle, coordinator.streamState.value)
        assertFalse(coordinator.hasConfiguredReceiver.value)
        assertEquals(null, settings.state.value.receiverEndpoint)
        assertEquals(1, controller.stopCount)
    }

    private fun coordinator(
        controller: FakeController,
        scope: kotlinx.coroutines.CoroutineScope,
        reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
        monitor: NetworkChangeMonitor = NoopNetworkChangeMonitor,
        settings: FakeSettings = FakeSettings(),
    ) = SenderConnectionCoordinator(
        controller = controller,
        settings = settings,
        logger = TestLogger,
        scope = scope,
        defaultEndpoint = endpoint,
        networkChangeMonitor = monitor,
        reconnectPolicy = reconnectPolicy,
        jitterSource = { NEUTRAL_JITTER_SAMPLE },
    )

    private class FakeController(
        private var failuresBeforeSuccess: Int = NO_FAILURES,
        private val alwaysUnavailable: Boolean = false,
    ) : StreamSessionController {
        private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
        override val state: StateFlow<StreamState> = stateFlow.asStateFlow()
        val attemptedEndpoints = mutableListOf<ReceiverEndpoint>()
        var startCount = 0
        var stopCount = 0
        private var nextGeneration = DirectStreamContract.FIRST_STREAM_GENERATION

        override suspend fun start(endpoint: ReceiverEndpoint, profile: VideoProfile): Result<Unit> {
            attemptedEndpoints += endpoint
            startCount += 1
            if (alwaysUnavailable || failuresBeforeSuccess-- > NO_FAILURES) {
                val failure = StreamFailure.StreamStartFailed(IllegalStateException("unavailable"))
                stateFlow.value = StreamState.Failed(failure)
                return Result.failure(StreamFailureException(failure))
            }
            stateFlow.value = StreamState.Streaming(
                session = StreamSession(
                    sessionId = "test-session",
                    endpoint = endpoint,
                    selectedCodec = VideoCodec.H264,
                    profile = profile,
                    bitrateBps = profile.h264BitrateBps,
                    mediaPort = DirectStreamContract.DEFAULT_MEDIA_PORT,
                    outputPixelFormat = OutputPixelFormat.NV12,
                    warnings = emptyList(),
                    streamGeneration = nextGeneration++,
                ),
                startedAtMillis = STREAM_START_TIME,
            )
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<Unit> {
            stopCount += 1
            stateFlow.value = StreamState.Idle
            return Result.success(Unit)
        }

        override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = Result.success(Unit)
    }

    private class FakeSettings : SenderSettingsRepository {
        private val stateFlow = MutableStateFlow(SenderSettings(profile = VideoProfiles.default))
        override val state: StateFlow<SenderSettings> = stateFlow.asStateFlow()

        override fun updateProfile(profile: VideoProfile) {
            stateFlow.value = stateFlow.value.copy(profile = profile)
        }

        override fun updateReceiverEndpoint(endpoint: ReceiverEndpoint?) {
            stateFlow.value = stateFlow.value.copy(receiverEndpoint = endpoint)
        }
    }

    private class FakeNetworkChangeMonitor : NetworkChangeMonitor {
        private val changeFlow = MutableSharedFlow<Unit>(extraBufferCapacity = NETWORK_EVENT_BUFFER_CAPACITY)
        override val changes: Flow<Unit> = changeFlow.asSharedFlow()

        override fun start() = Unit

        override fun stop() = Unit

        suspend fun emitChange() {
            changeFlow.emit(Unit)
        }
    }

    private object TestLogger : AppLogger {
        override fun debug(message: String, fields: Map<String, Any?>) = Unit
        override fun info(message: String, fields: Map<String, Any?>) = Unit
        override fun warn(message: String, cause: Throwable?, fields: Map<String, Any?>) = Unit
        override fun error(message: String, cause: Throwable?, fields: Map<String, Any?>) = Unit
    }

    private object NoopNetworkChangeMonitor : NetworkChangeMonitor {
        override val changes: Flow<Unit> = kotlinx.coroutines.flow.emptyFlow()
        override fun start() = Unit
        override fun stop() = Unit
    }

    private companion object {
        val endpoint = ReceiverEndpoint(
            "127.0.0.1",
            DirectStreamContract.DEFAULT_CONTROL_PORT,
            "Test receiver",
        )
        const val RETRY_DELAY_MILLIS = 250L
        const val LONG_RETRY_DELAY_MILLIS = 5_000L
        const val RETRY_ATTEMPT_COUNT = 3
        const val NO_FAILURES = 0
        const val NEUTRAL_JITTER_SAMPLE = 0.5
        const val NETWORK_EVENT_BUFFER_CAPACITY = 1
        const val STREAM_START_TIME = 100L

        fun testReconnectPolicy(maximumDelayMillis: Long = RETRY_DELAY_MILLIS) = ReconnectPolicy(
            initialDelayMillis = RETRY_DELAY_MILLIS,
            maximumDelayMillis = maximumDelayMillis,
            jitterFraction = 0.0,
        )
    }
}
