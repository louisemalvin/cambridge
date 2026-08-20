package dev.cambridge.sender

import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.media.capabilities.EncoderCapabilityProbe
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.media.camera.CameraController
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraPermissionRequiredException
import dev.cambridge.sender.media.camera.CameraPreviewSurface
import dev.cambridge.sender.media.camera.PhysicalLensOption
import dev.cambridge.sender.media.camera.SessionTransform
import dev.cambridge.sender.media.camera.toDisplayOrientation
import dev.cambridge.sender.media.streaming.StreamEngine
import dev.cambridge.sender.media.streaming.StreamEngineEvent
import dev.cambridge.sender.model.EncoderAcceleration
import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.EncoderModeCapability
import dev.cambridge.sender.model.ReceiverEndpoint
import dev.cambridge.sender.model.StreamConfiguration
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.StreamVideoConfiguration
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.platform.service.ForegroundStreamingController
import dev.cambridge.sender.session.StreamSessionControllerImpl
import dev.cambridge.sender.session.VideoProfiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSessionControllerTest {
    @Test
    fun startAndStopAreIdempotent() = runTest {
        val engine = FakeEngine()
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, configuration()).isSuccess)
        assertTrue(controller.start(endpoint, configuration()).isFailure)
        assertEquals(1, engine.prepareCount)
        assertTrue(controller.state.value is StreamState.Streaming)

        assertTrue(controller.stop().isSuccess)
        assertTrue(controller.stop().isSuccess)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun prepareFailureReleasesTheCamBridgeSession() = runTest {
        val engine = FakeEngine(prepareFailure = IllegalStateException("test"))
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, configuration()).isFailure)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
        assertTrue(controller.state.value is StreamState.Failed)
    }

    @Test
    fun selectedEncoderAndBitrateReachTheEncoderConfiguration() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine, FakeForeground(), backgroundScope)
        val selectedBitrate = VideoProfiles.PROFILE_2K30.minimumBitrateBps

        assertTrue(
            controller.start(
                endpoint = endpoint,
                configuration = configuration(VideoProfiles.PROFILE_2K30, selectedBitrate),
            ).isSuccess,
        )

        assertEquals("fake-h264", engine.lastConfiguration?.encoderName)
        assertEquals(selectedBitrate, engine.lastConfiguration?.bitrateBps)
    }

    @Test
    fun unsupportedEncoderCapabilityFailsBeforePreparingTheEngine() = runTest {
        val engine = FakeEngine()
        val controller = controller(
            engine = engine,
            foreground = FakeForeground(),
            scope = backgroundScope,
            probe = FakeProbe(supported = false),
        )

        assertTrue(controller.start(endpoint, configuration()).isFailure)

        assertEquals(0, engine.prepareCount)
        assertTrue(
            (controller.state.value as StreamState.Failed).failure is StreamFailure.EncoderPreparationFailed,
        )
    }

    @Test
    fun permissionFailureIsReportedAsPermissionDeniedAndNotReceiverUnavailable() = runTest {
        val controller = controller(
            engine = FakeEngine(),
            foreground = FakeForeground(),
            scope = backgroundScope,
            cameraController = FakeCameraController(
                snapshotFailure = CameraPermissionRequiredException("camera permission missing"),
            ),
        )

        assertTrue(controller.start(endpoint, configuration()).isFailure)

        assertEquals(
            StreamState.Failed(StreamFailure.CameraPermissionDenied),
            controller.state.value,
        )
    }

    @Test
    fun transformFailureReleasesResourcesExactlyOnce() = runTest {
        val engine = FakeEngine()
        val foreground = FakeForeground()
        val controller = controller(
            engine = engine,
            foreground = foreground,
            scope = backgroundScope,
            cameraController = FakeCameraController(
                snapshotFailure = IllegalStateException("camera transform failed"),
            ),
        )

        assertTrue(controller.start(endpoint, configuration()).isFailure)
        assertEquals(1, engine.stopCount)
        assertEquals(1, engine.releaseCount)
        assertEquals(1, foreground.stopCount)

        assertTrue(controller.stop().isSuccess)
        assertEquals(1, engine.stopCount)
        assertEquals(1, engine.releaseCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun engineStartFailureCleansUpThePreparedSession() = runTest {
        val engineFailure = IllegalStateException("receiver rejected stream")
        val engine = FakeEngine(startFailure = engineFailure)
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, configuration()).isFailure)

        assertTrue(controller.state.value is StreamState.Failed)
        assertTrue((controller.state.value as StreamState.Failed).failure is StreamFailure.StreamStartFailed)
        assertEquals(1, engine.stopCount)
        assertEquals(1, engine.releaseCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun bitrateUpdatesRequireAnActiveSessionAndReachTheEngine() = runTest {
        val engine = FakeEngine()
        val controller = controller(engine, FakeForeground(), backgroundScope)
        val updatedBitrate = profile.defaultBitrateBps + profile.bitrateStepBps

        assertTrue(controller.updateBitrate(profile.defaultBitrateBps).isFailure)
        assertTrue(controller.start(endpoint, configuration()).isSuccess)
        assertTrue(controller.updateBitrate(updatedBitrate).isSuccess)

        assertEquals(updatedBitrate, engine.lastBitrate)
    }

    @Test
    fun camBridgeEngineDisconnectFailsAndCleansUpTheSession() = runTest {
        val engine = FakeEngineWithEvents()
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, configuration()).isSuccess)
        testScheduler.runCurrent()
        engine.emit(StreamEngineEvent.Disconnected(engine.lastStartEndpoint!!.generation))
        testScheduler.runCurrent()

        assertEquals(StreamState.Failed(StreamFailure.NetworkDisconnected), controller.state.value)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun fatalRtpFailureEndsTheWholeSession() = runTest {
        val engine = FakeEngineWithEvents()
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, configuration()).isSuccess)
        testScheduler.runCurrent()
        engine.emit(
            StreamEngineEvent.FatalFailure(
                StreamFailure.RtpTransportFailed(IllegalStateException("socket failed")),
                generation = engine.lastStartEndpoint!!.generation,
            ),
        )
        testScheduler.runCurrent()

        assertTrue(controller.state.value is StreamState.Failed)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun fatalCameraFailureEndsTheWholeSession() = runTest {
        val engine = FakeEngineWithEvents()
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, configuration()).isSuccess)
        testScheduler.runCurrent()
        engine.emit(
            StreamEngineEvent.FatalFailure(
                StreamFailure.CameraUnavailable,
                generation = engine.lastStartEndpoint!!.generation,
            ),
        )
        testScheduler.runCurrent()

        assertEquals(StreamState.Failed(StreamFailure.CameraUnavailable), controller.state.value)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun staleEventFromAnEarlierGenerationCannotStopTheReplacementSession() = runTest {
        val engine = FakeEngineWithEvents()
        val controller = controller(engine, FakeForeground(), backgroundScope)

        assertTrue(controller.start(endpoint, configuration()).isSuccess)
        val firstGeneration = engine.lastStartEndpoint!!.generation
        assertTrue(controller.stop().isSuccess)
        assertTrue(controller.start(endpoint, configuration()).isSuccess)
        val secondGeneration = engine.lastStartEndpoint!!.generation
        assertTrue(firstGeneration != secondGeneration)

        engine.emit(StreamEngineEvent.Disconnected(firstGeneration))
        testScheduler.runCurrent()

        assertTrue(controller.state.value is StreamState.Streaming)
        assertEquals(1, engine.stopCount)
    }

    private fun controller(
        engine: StreamEngine,
        foreground: FakeForeground,
        scope: kotlinx.coroutines.CoroutineScope,
        probe: EncoderCapabilityProbe = FakeProbe(),
        cameraController: CameraController? = null,
    ) = StreamSessionControllerImpl(
        capabilityProbe = probe,
        streamEngine = engine,
        foreground = foreground,
        logger = TestLogger,
        scope = scope,
        cameraController = cameraController,
    )

    private class FakeProbe(
        private val supported: Boolean = true,
    ) : EncoderCapabilityProbe {
        override suspend fun getCapabilities(profiles: List<VideoProfile>): List<EncoderCapability> = listOf(
            EncoderCapability(
                codec = VideoCodec.H264,
                implementationName = "fake-h264",
                acceleration = EncoderAcceleration.HARDWARE,
                surfaceInputSupported = true,
                modes = profiles.map { profile ->
                    EncoderModeCapability(
                        modeId = profile.id,
                        sizeAndRateSupported = supported,
                        minimumBitrateBps = profile.minimumBitrateBps,
                        maximumBitrateBps = profile.maximumBitrateBps,
                    )
                },
            ),
        )
    }

    private class FakeEngine(
        private val prepareFailure: Throwable? = null,
        private val startFailure: Throwable? = null,
    ) : StreamEngine {
        var prepareCount = 0
        var stopCount = 0
        var releaseCount = 0
        var lastConfiguration: StreamConfiguration? = null
        var lastBitrate: Int? = null
        override val events: Flow<StreamEngineEvent> = emptyFlow()

        override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> {
            prepareCount += 1
            lastConfiguration = configuration
            return prepareFailure?.let(Result.Companion::failure) ?: Result.success(Unit)
        }

        override suspend fun start(endpoint: dev.cambridge.sender.model.CamBridgeStreamEndpoint): Result<Unit> =
            startFailure?.let(Result.Companion::failure) ?: Result.success(Unit)

        override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> {
            lastBitrate = bitrateBps
            return Result.success(Unit)
        }

        override suspend fun stop(): Result<Unit> {
            stopCount += 1
            return Result.success(Unit)
        }

        override suspend fun release() {
            releaseCount += 1
        }
    }

    private class FakeEngineWithEvents : StreamEngine {
        var stopCount = 0
        var lastStartEndpoint: dev.cambridge.sender.model.CamBridgeStreamEndpoint? = null
        private val eventFlow = MutableSharedFlow<StreamEngineEvent>()
        override val events: Flow<StreamEngineEvent> = eventFlow

        suspend fun emit(event: StreamEngineEvent) {
            eventFlow.emit(event)
        }

        override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> = Result.success(Unit)

        override suspend fun start(endpoint: dev.cambridge.sender.model.CamBridgeStreamEndpoint): Result<Unit> {
            lastStartEndpoint = endpoint
            return Result.success(Unit)
        }

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

    private class FakeCameraController(
        private val snapshotFailure: Throwable? = null,
    ) : CameraController {
        private val stateFlow = MutableStateFlow(CameraInteractionState.inactive())
        override val state: StateFlow<CameraInteractionState> = stateFlow

        override suspend fun prepareCamera() = Unit

        override suspend fun setZoomRatio(zoomRatio: Float) = Unit

        override suspend fun resetZoom() = Unit

        override suspend fun setAntiFlickerMode(mode: AntiFlickerMode) = Unit

        override suspend fun selectPhysicalLens(lens: PhysicalLensOption) = Unit

        override suspend fun setPreviewSurface(surface: CameraPreviewSurface?) = Unit

        override suspend fun snapshotSessionTransform(
            codedWidth: Int,
            codedHeight: Int,
            orientation: StreamOrientation,
        ): SessionTransform {
            snapshotFailure?.let { throw it }
            return SessionTransform.forProfile(
                displayOrientation = orientation.toDisplayOrientation(),
                codedWidth = codedWidth,
                codedHeight = codedHeight,
            )
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
        val endpoint = ReceiverEndpoint(
            "127.0.0.1",
            CamBridgeStreamContract.DEFAULT_CONTROL_PORT,
            "Test receiver",
        )

        fun configuration(
            selectedProfile: VideoProfile = profile,
            bitrateBps: Int = selectedProfile.defaultBitrateBps,
        ) = StreamVideoConfiguration(
            encoderName = "fake-h264",
            profile = selectedProfile,
            bitrateBps = bitrateBps,
            streamOrientation = StreamOrientation.LANDSCAPE,
        )
    }
}
