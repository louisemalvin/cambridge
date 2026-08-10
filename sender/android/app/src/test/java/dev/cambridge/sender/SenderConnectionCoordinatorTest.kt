package dev.cambridge.sender

import dev.cambridge.discovery.DiscoveredReceiverEndpoint
import dev.cambridge.discovery.EmptyReceiverDiscovery
import dev.cambridge.discovery.ReceiverDiscovery
import dev.cambridge.discovery.ReceiverDiscoveryPhase
import dev.cambridge.discovery.ReceiverDiscoverySnapshot
import dev.cambridge.sender.connection.SenderConnectionCoordinator
import dev.cambridge.sender.connection.control.ReceiverProbe
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.model.OutputPixelFormat
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.ReceiverCapabilities
import dev.cambridge.sender.model.ReceiverProbeState
import dev.cambridge.sender.model.SenderSettings
import dev.cambridge.sender.model.SenderSettingsRepository
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamFailureException
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.StreamSession
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.StreamSessionController
import dev.cambridge.sender.session.VideoProfiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun probingReportsReceiverCapabilities() = runTest {
        val receiverProbe = FakeReceiverProbe()
        val coordinator = coordinator(controller = FakeController(), scope = backgroundScope, receiverProbe = receiverProbe)

        assertTrue(coordinator.probeReceiver().isSuccess)
        assertEquals("OBS receiver", coordinator.activeReceiverName.value)
        val state = coordinator.receiverProbeState.value as ReceiverProbeState.Available
        assertEquals("cambridge-obs-source", state.capabilities.receiverId)
        assertEquals(endpoint.host, receiverProbe.probedEndpoint?.host)
    }

    @Test
    fun probingPrefersDiscoveredReceiverWhenNoEndpointIsConfigured() = runTest {
        val discoveredEndpoint = ReceiverEndpoint("10.0.0.8", endpoint.controlPort, "Discovered OBS")
        val receiverProbe = FakeReceiverProbe()
        val discovery = FakeReceiverDiscovery(discoveredEndpoint)
        val coordinator = coordinator(
            controller = FakeController(),
            scope = backgroundScope,
            receiverProbe = receiverProbe,
            receiverDiscovery = discovery,
        )

        assertTrue(coordinator.probeReceiver().isSuccess)
        assertEquals(discoveredEndpoint.host, receiverProbe.probedEndpoint?.host)
    }

    @Test
    fun configuringManualReceiverHostPersistsAndProbesIt() = runTest {
        val settings = FakeSettings()
        val receiverProbe = FakeReceiverProbe()
        val coordinator = coordinator(
            controller = FakeController(),
            scope = backgroundScope,
            settings = settings,
            receiverProbe = receiverProbe,
        )

        assertTrue(coordinator.configureReceiverHost("10.0.0.24").isSuccess)

        assertEquals("10.0.0.24", receiverProbe.probedEndpoint?.host)
        assertEquals("10.0.0.24", settings.state.value.receiverEndpoint?.host)
        assertEquals(endpoint.controlPort, settings.state.value.receiverEndpoint?.controlPort)
        assertEquals("cambridge-obs-source", settings.state.value.receiverEndpoint?.receiverId)
    }

    @Test
    fun multipleDiscoveredReceiversRequireAnExplicitSelection() = runTest {
        val office = ReceiverEndpoint("10.0.0.8", endpoint.controlPort, "Office")
        val studio = ReceiverEndpoint("10.0.0.9", endpoint.controlPort, "Studio")
        val discovery = FakeReceiverDiscovery(office, studio)
        val receiverProbe = FakeReceiverProbe { target ->
            capabilities(
                receiverId = "receiver-${target.host}",
                displayName = target.displayName,
            )
        }
        val coordinator = coordinator(
            controller = FakeController(),
            scope = backgroundScope,
            receiverProbe = receiverProbe,
            receiverDiscovery = discovery,
        )

        assertTrue(coordinator.probeReceiver().isFailure)

        assertEquals(ReceiverProbeState.SelectionRequired, coordinator.receiverProbeState.value)
        assertEquals(listOf("Office", "Studio"), coordinator.receiverCandidates.value.map {
            it.capabilities.displayName
        })
        assertTrue(coordinator.startStream().isFailure)
    }

    @Test
    fun selectedDiscoveredReceiverIsPersistedAndUsedForStart() = runTest {
        val office = ReceiverEndpoint("10.0.0.8", endpoint.controlPort, "Office")
        val studio = ReceiverEndpoint("10.0.0.9", endpoint.controlPort, "Studio")
        val settings = FakeSettings()
        val controller = FakeController()
        val discovery = FakeReceiverDiscovery(office, studio)
        val receiverProbe = FakeReceiverProbe { target ->
            capabilities(
                receiverId = "receiver-${target.host}",
                displayName = target.displayName,
            )
        }
        val coordinator = coordinator(
            controller = controller,
            scope = backgroundScope,
            settings = settings,
            receiverProbe = receiverProbe,
            receiverDiscovery = discovery,
        )
        coordinator.probeReceiver()

        assertTrue(coordinator.selectReceiver("receiver-${studio.host}").isSuccess)
        assertTrue(coordinator.startStream().isSuccess)

        assertEquals(studio.host, settings.state.value.receiverEndpoint?.host)
        assertEquals("receiver-${studio.host}", settings.state.value.receiverEndpoint?.receiverId)
        assertEquals(studio.host, controller.attemptedEndpoints.single().host)
    }

    @Test
    fun activeDiscoveryProbesNewAddressesAndStopsWithTheSetupLifecycle() = runTest {
        val discovery = FakeReceiverDiscovery()
        val receiverProbe = FakeReceiverProbe()
        val coordinator = coordinator(
            controller = FakeController(),
            scope = backgroundScope,
            receiverProbe = receiverProbe,
            receiverDiscovery = discovery,
        )

        coordinator.startReceiverDiscovery()
        testScheduler.runCurrent()
        discovery.publish(ReceiverEndpoint("100.64.0.10", endpoint.controlPort, "Studio OBS"))
        testScheduler.runCurrent()

        assertTrue(receiverProbe.probedEndpoints.any { probed -> probed.host == "100.64.0.10" })
        assertEquals("OBS receiver", coordinator.activeReceiverName.value)

        coordinator.stopReceiverDiscovery()

        assertEquals(1, discovery.startCount)
        assertEquals(1, discovery.stopCount)
    }

    private fun coordinator(
        controller: FakeController,
        scope: kotlinx.coroutines.CoroutineScope,
        settings: FakeSettings = FakeSettings(),
        receiverProbe: ReceiverProbe = FakeReceiverProbe(),
        receiverDiscovery: ReceiverDiscovery = EmptyReceiverDiscovery,
    ) = SenderConnectionCoordinator(
        controller = controller,
        settings = settings,
        logger = TestLogger,
        scope = scope,
        defaultEndpoint = endpoint,
        receiverProbe = receiverProbe,
        receiverDiscovery = receiverDiscovery,
    )

    private class FakeReceiverProbe(
        private val response: (ReceiverEndpoint) -> ReceiverCapabilities = {
            capabilities("cambridge-obs-source", "OBS receiver")
        },
    ) : ReceiverProbe {
        var probedEndpoint: ReceiverEndpoint? = null
        val probedEndpoints = mutableListOf<ReceiverEndpoint>()

        override suspend fun probe(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> {
            probedEndpoint = endpoint
            probedEndpoints += endpoint
            return Result.success(response(endpoint))
        }
    }

    private class FakeReceiverDiscovery(
        vararg endpoints: ReceiverEndpoint,
    ) : ReceiverDiscovery {
        private val state = MutableStateFlow(
            ReceiverDiscoverySnapshot(
                phase = ReceiverDiscoveryPhase.RUNNING,
                endpoints = endpoints.map { endpoint -> endpoint.toDiscoveredEndpoint() },
            ),
        )
        override val snapshot: StateFlow<ReceiverDiscoverySnapshot> = state.asStateFlow()
        var startCount = 0
        var stopCount = 0

        override fun start() {
            startCount += 1
            state.value = state.value.copy(phase = ReceiverDiscoveryPhase.RUNNING)
        }

        override fun stop() {
            stopCount += 1
            state.value = ReceiverDiscoverySnapshot.Stopped
        }

        fun publish(vararg endpoints: ReceiverEndpoint) {
            state.value = ReceiverDiscoverySnapshot(
                phase = ReceiverDiscoveryPhase.RUNNING,
                endpoints = endpoints.map { endpoint -> endpoint.toDiscoveredEndpoint() },
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
        private var nextGeneration = CamBridgeStreamContract.FIRST_STREAM_GENERATION

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
                    bitrateBps = profile.defaultBitrateBps,
                    mediaPort = CamBridgeStreamContract.DEFAULT_MEDIA_PORT,
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
        fun capabilities(receiverId: String, displayName: String) = ReceiverCapabilities(
            receiverId = receiverId,
            displayName = displayName,
            maxLongEdge = 3_840,
            maxShortEdge = 2_160,
        )

        val endpoint = ReceiverEndpoint(
            "127.0.0.1",
            CamBridgeStreamContract.DEFAULT_CONTROL_PORT,
            "Test receiver",
        )
        const val STREAM_START_TIME = 100L

        fun ReceiverEndpoint.toDiscoveredEndpoint() = DiscoveredReceiverEndpoint(
            serviceName = displayName,
            host = host,
            port = controlPort,
        )
    }
}
