package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.control.ReceiverProbe
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverProbeState
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.StreamSession
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.session.VideoProfiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun failedConnectDoesNotRetry() = runTest {
        val controller = FakeController(alwaysUnavailable = true)
        val coordinator = coordinator(controller, backgroundScope)

        assertTrue(coordinator.connectToReceiver(endpoint).isFailure)
        testScheduler.runCurrent()

        assertEquals(1, controller.attemptedEndpoints.size)
        assertTrue(coordinator.streamState.value is StreamState.Failed)
    }

    @Test
    fun stopDoesNotStartAnotherConnection() = runTest {
        val controller = FakeController()
        val coordinator = coordinator(controller, backgroundScope)

        assertTrue(coordinator.connectToReceiver(endpoint).isSuccess)
        assertTrue(coordinator.stop().isSuccess)

        assertEquals(StreamState.Idle, coordinator.streamState.value)
        assertEquals(1, controller.attemptedEndpoints.size)
        assertEquals(1, controller.stopCount)
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

    @Test
    fun probingReportsReceiverCapabilities() = runTest {
        val receiverProbe = FakeReceiverProbe()
        val coordinator = coordinator(controller = FakeController(), scope = backgroundScope, receiverProbe = receiverProbe)

        assertTrue(coordinator.probeReceiver().isSuccess)
        assertEquals("OBS receiver", coordinator.activeReceiverName.value)
        val state = coordinator.receiverProbeState.value as ReceiverProbeState.Available
        assertEquals("obs-direct-webcam-source", state.capabilities.receiverId)
        assertEquals(endpoint.host, receiverProbe.probedEndpoint?.host)
    }

    private fun coordinator(
        controller: FakeController,
        scope: kotlinx.coroutines.CoroutineScope,
        settings: FakeSettings = FakeSettings(),
        receiverProbe: ReceiverProbe = FakeReceiverProbe(),
    ) = SenderConnectionCoordinator(
        controller = controller,
        settings = settings,
        logger = TestLogger,
        scope = scope,
        defaultEndpoint = endpoint,
        receiverProbe = receiverProbe,
    )

    private class FakeReceiverProbe : ReceiverProbe {
        var probedEndpoint: ReceiverEndpoint? = null

        override suspend fun probe(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> {
            probedEndpoint = endpoint
            return Result.success(
                ReceiverCapabilities(
                    receiverId = "obs-direct-webcam-source",
                    displayName = "OBS receiver",
                    profileIds = listOf("1080p30", "2k30"),
                    maxLongEdge = 3_840,
                    maxShortEdge = 2_160,
                ),
            )
        }
    }

    private class FakeController(
        private val alwaysUnavailable: Boolean = false,
    ) : StreamSessionController {
        private val stateFlow = MutableStateFlow<StreamState>(StreamState.Idle)
        override val state: StateFlow<StreamState> = stateFlow.asStateFlow()
        val attemptedEndpoints = mutableListOf<ReceiverEndpoint>()
        var startCount = 0
        var stopCount = 0
        private var nextGeneration = DirectStreamContract.FIRST_STREAM_GENERATION

        override suspend fun start(
            endpoint: ReceiverEndpoint,
            profile: VideoProfile,
            orientation: StreamOrientation,
        ): Result<Unit> {
            attemptedEndpoints += endpoint
            startCount += 1
            if (alwaysUnavailable) {
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

        override fun updateStreamOrientation(orientation: StreamOrientation) {
            stateFlow.value = stateFlow.value.copy(streamOrientation = orientation)
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
        val endpoint = ReceiverEndpoint(
            "127.0.0.1",
            DirectStreamContract.DEFAULT_CONTROL_PORT,
            "Test receiver",
        )
        const val STREAM_START_TIME = 100L
    }
}
