package dev.mobilewebcam.sender.streaming.rootencoder

import android.content.Context
import android.view.Surface
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.udp.UdpStream
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.streaming.StreamEngine
import dev.mobilewebcam.sender.streaming.StreamEngineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class RootEncoderStreamEngine(context: Context) : StreamEngine {
    private val applicationContext = context.applicationContext
    private val eventFlow = MutableSharedFlow<StreamEngineEvent>(extraBufferCapacity = 32)
    private var stream: UdpStream? = null

    override val events: Flow<StreamEngineEvent> = eventFlow

    override suspend fun prepare(
        previewSurface: Surface?,
        configuration: StreamConfiguration,
    ): Result<Unit> {
        var encoder: UdpStream? = null
        return try {
            check(stream == null) { "A stream is already prepared" }
            val createdEncoder = UdpStream(
                applicationContext,
                RootEncoderEventAdapter(eventFlow),
                Camera2Source(applicationContext),
                NoAudioSource(),
            )
            encoder = createdEncoder
            val video = configuration.toRootEncoderVideo()
            createdEncoder.setVideoCodec(configuration.codec.toRootEncoder())
            createdEncoder.getStreamClient().setOnlyVideo(true)
            check(createdEncoder.prepareVideo(
                video.width,
                video.height,
                video.bitrateBps,
                video.fps,
                video.keyframeIntervalSeconds,
            )) { "RootEncoder video preparation failed" }
            check(createdEncoder.prepareAudio(44_100, false, 64_000)) {
                "RootEncoder audio preparation failed"
            }
            if (previewSurface != null) {
                createdEncoder.startPreview(previewSurface, video.width, video.height)
            }
            stream = createdEncoder
            Result.success(Unit)
        } catch (cause: Throwable) {
            runCatching { encoder?.release() }
            Result.failure(
                StreamFailureException(
                    StreamFailure.EncoderPreparationFailed(configuration.codec, cause),
                    cause,
                ),
            )
        }
    }

    override suspend fun start(
        receiverHost: String,
        mediaPort: Int,
    ): Result<Unit> = runCatching {
        val encoder = stream ?: error("Stream has not been prepared")
        encoder.startStream("udp://$receiverHost:$mediaPort")
    }.recoverCatching { cause ->
        throw StreamFailureException(StreamFailure.StreamStartFailed(cause), cause)
    }

    override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = runCatching {
        check(bitrateBps > 0) { "Bitrate must be positive" }
        (stream ?: error("Stream has not been prepared")).setVideoBitrateOnFly(bitrateBps)
    }

    override suspend fun stop(): Result<Unit> = runCatching {
        stream?.let { encoder ->
            if (encoder.isStreaming) encoder.stopStream()
            if (encoder.isOnPreview) encoder.stopPreview()
        }
    }

    override suspend fun release() {
        stream?.release()
        stream = null
    }
}
