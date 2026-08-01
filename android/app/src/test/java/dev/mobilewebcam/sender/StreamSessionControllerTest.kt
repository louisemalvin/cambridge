package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.control.ReceiverControlClient
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
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.platform.ForegroundStreamingController
import dev.mobilewebcam.sender.session.CodecNegotiator
import dev.mobilewebcam.sender.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.streaming.StreamEngine
import dev.mobilewebcam.sender.streaming.StreamEngineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSessionControllerTest {
    @Test
    fun startAndStopAreIdempotentAndDoNotDuplicatePreparation() = runTest {
        val receiver = FakeReceiver()
        val engine = FakeEngine()
        val foreground = FakeForeground()
        val controller = controller(receiver, engine, foreground, backgroundScope)

        assertTrue(controller.start("127.0.0.1", 5001, CodecPreference.AUTO_PREFER_H265, profile, null).isSuccess)
        assertTrue(controller.start("127.0.0.1", 5001, CodecPreference.AUTO_PREFER_H265, profile, null).isFailure)
        assertEquals(1, engine.prepareCount)

        assertTrue(controller.stop().isSuccess)
        assertTrue(controller.stop().isSuccess)
        assertEquals(1, engine.stopCount)
        assertEquals(1, foreground.stopCount)
        assertEquals(1, receiver.stopCount)
    }

    @Test
    fun encoderPreparationFailureStopsPreparedReceiverSession() = runTest {
        val receiver = FakeReceiver()
        val engine = FakeEngine(prepareFailure = IllegalStateException("test"))
        val controller = controller(receiver, engine, FakeForeground(), backgroundScope)

        assertTrue(controller.start("127.0.0.1", 5001, CodecPreference.FORCE_H264, profile, null).isFailure)
        assertEquals(1, receiver.stopCount)
    }

    private fun controller(
        receiver: FakeReceiver,
        engine: FakeEngine,
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

    private class FakeProbe : dev.mobilewebcam.sender.capabilities.EncoderCapabilityProbe {
        override suspend fun getCapabilities(profiles: List<VideoProfile>): List<EncoderCapability> =
            profiles.flatMap { profile ->
                VideoCodec.entries.map { codec ->
                    EncoderCapability(
                        codec = codec,
                        profileId = profile.id,
                        supported = true,
                        acceleration = EncoderAcceleration.HARDWARE,
                        encoderName = "fake-$codec",
                    )
                }
            }
    }

    private class FakeReceiver : ReceiverControlClient {
        var stopCount = 0
        override suspend fun health(endpoint: ReceiverEndpoint): Result<ReceiverHealth> =
            Result.success(ReceiverHealth("ready", 1))

        override suspend fun capabilities(endpoint: ReceiverEndpoint): Result<ReceiverCapabilities> =
            Result.success(receiverCapabilities())

        override suspend fun prepareSession(
            endpoint: ReceiverEndpoint,
            request: PrepareSessionRequest,
        ): Result<NegotiatedSession> = Result.success(
            NegotiatedSession(
                sessionId = "test-session",
                endpoint = endpoint,
                selectedCodec = request.preferredCodecs.first(),
                profile = request.profile,
                bitrateBps = request.bitrateByCodec.getValue(request.preferredCodecs.first()),
                mediaPort = 5000,
                outputPixelFormat = OutputPixelFormat.YUY2,
                warnings = emptyList(),
            ),
        )

        override suspend fun stopSession(endpoint: ReceiverEndpoint, sessionId: String): Result<Unit> {
            stopCount += 1
            return Result.success(Unit)
        }
    }

    private class FakeEngine(
        private val prepareFailure: Throwable? = null,
    ) : StreamEngine {
        var prepareCount = 0
        var stopCount = 0
        override val events: Flow<StreamEngineEvent> = emptyFlow()

        override suspend fun prepare(
            previewSurface: android.view.Surface?,
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

        fun receiverCapabilities() = ReceiverCapabilities(
            protocolVersion = 1,
            mediaPort = 5000,
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
