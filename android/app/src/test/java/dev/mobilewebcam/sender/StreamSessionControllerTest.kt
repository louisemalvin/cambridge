package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.media.capabilities.EncoderCapabilityProbe
import dev.mobilewebcam.sender.media.streaming.StreamEngine
import dev.mobilewebcam.sender.media.streaming.StreamEngineEvent
import dev.mobilewebcam.sender.model.EncoderAcceleration
import dev.mobilewebcam.sender.model.EncoderCapability
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.platform.service.ForegroundStreamingController
import dev.mobilewebcam.sender.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.session.VideoProfiles
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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

        assertTrue(controller.start(endpoint, profile, StreamOrientation.LANDSCAPE).isSuccess)
        assertTrue(controller.start(endpoint, profile, StreamOrientation.LANDSCAPE).isFailure)
        assertEquals(1, engine.prepareCount)
        assertTrue(controller.state.value is StreamState.Streaming)

        assertTrue(controller.stop().isSuccess)
        assertTrue(controller.stop().isSuccess)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
    }

    @Test
    fun prepareFailureReleasesTheDirectSession() = runTest {
        val engine = FakeEngine(prepareFailure = IllegalStateException("test"))
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, profile, StreamOrientation.LANDSCAPE).isFailure)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
        assertTrue(controller.state.value is StreamState.Failed)
    }

    @Test
    fun directEngineDisconnectFailsAndCleansUpTheSession() = runTest {
        val engine = FakeEngineWithEvents()
        val foreground = FakeForeground()
        val controller = controller(engine, foreground, backgroundScope)

        assertTrue(controller.start(endpoint, profile, StreamOrientation.LANDSCAPE).isSuccess)
        testScheduler.runCurrent()
        engine.emit(StreamEngineEvent.Disconnected)
        testScheduler.runCurrent()

        assertEquals(StreamState.Failed(StreamFailure.NetworkDisconnected), controller.state.value)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
    }

    private fun controller(
        engine: StreamEngine,
        foreground: FakeForeground,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = StreamSessionControllerImpl(
        capabilityProbe = FakeProbe(),
        streamEngine = engine,
        foreground = foreground,
        logger = TestLogger,
        scope = scope,
    )

    private class FakeProbe : EncoderCapabilityProbe {
        override suspend fun getCapabilities(profiles: List<VideoProfile>): List<EncoderCapability> =
            profiles.map { profile ->
                EncoderCapability(
                    codec = VideoCodec.H264,
                    profileId = profile.id,
                    supported = true,
                    acceleration = EncoderAcceleration.HARDWARE,
                    encoderName = "fake-h264",
                )
            }
    }

    private class FakeEngine(
        private val prepareFailure: Throwable? = null,
    ) : StreamEngine {
        var prepareCount = 0
        var stopCount = 0
        override val events: Flow<StreamEngineEvent> = emptyFlow()

        override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> {
            prepareCount += 1
            return prepareFailure?.let(Result.Companion::failure) ?: Result.success(Unit)
        }

        override suspend fun start(endpoint: dev.mobilewebcam.sender.model.DirectStreamEndpoint): Result<Unit> =
            Result.success(Unit)

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

        override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> = Result.success(Unit)

        override suspend fun start(endpoint: dev.mobilewebcam.sender.model.DirectStreamEndpoint): Result<Unit> =
            Result.success(Unit)

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
        val endpoint = ReceiverEndpoint(
            "127.0.0.1",
            DirectStreamContract.DEFAULT_CONTROL_PORT,
            "Test receiver",
        )
    }
}
