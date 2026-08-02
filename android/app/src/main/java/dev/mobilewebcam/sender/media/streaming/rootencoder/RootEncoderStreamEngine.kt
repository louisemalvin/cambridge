package dev.mobilewebcam.sender.media.streaming.rootencoder

import android.content.Context
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.extrasources.CameraXSource
import com.pedro.library.udp.UdpStream
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.media.camera.PhysicalLensOption
import dev.mobilewebcam.sender.media.camera.RootEncoderCameraSourceFactory
import dev.mobilewebcam.sender.config.CameraZoom
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.media.streaming.StreamEngine
import dev.mobilewebcam.sender.media.streaming.StreamEngineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RootEncoderStreamEngine(
    context: Context,
    private val logger: AppLogger = AndroidAppLogger,
) : StreamEngine, CameraController {
    private val applicationContext = context.applicationContext
    private val eventFlow = MutableSharedFlow<StreamEngineEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )
    private val cameraMutex = Mutex()
    private val cameraState = MutableStateFlow(CameraInteractionState())
    private var stream: UdpStream? = null
    private var cameraSource: CameraXSource? = null
    private var previewSurface: CameraPreviewSurface? = null
    private var diagnosticRunId: String? = null
    private var diagnosticSessionId: String? = null

    override val events: Flow<StreamEngineEvent> = eventFlow
    override val state: StateFlow<CameraInteractionState> = cameraState.asStateFlow()

    override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> =
        cameraMutex.withLock {
            var encoder: UdpStream? = null
            return@withLock try {
                check(stream == null) { "A stream is already prepared" }
                diagnosticRunId = configuration.runId
                diagnosticSessionId = configuration.sessionId
                val source = RootEncoderCameraSourceFactory(applicationContext).createCameraXSource()
                val createdEncoder = UdpStream(
                    applicationContext,
                    RootEncoderEventAdapter(eventFlow),
                    source,
                    NoAudioSource(),
                )
                encoder = createdEncoder
                cameraSource = source
                stream = createdEncoder
                val video = configuration.toRootEncoderVideo()
                createdEncoder.setVideoCodec(configuration.codec.toRootEncoder())
                createdEncoder.getStreamClient().setOnlyVideo(true)
                check(createdEncoder.prepareVideo(
                    video.width,
                    video.height,
                    video.bitrateBps,
                    video.fps,
                    video.keyframeIntervalSeconds,
                    video.rotationDegrees,
                )) { "RootEncoder video preparation failed" }
                check(createdEncoder.prepareAudio(AUDIO_SAMPLE_RATE_HZ, false, AUDIO_BITRATE_BPS)) {
                    "RootEncoder audio preparation failed"
                }
                previewSurface?.let { target ->
                    attachPreviewIfValidLocked(createdEncoder, target)
                }
                updateCameraStateLocked()
                diagnosticEvent(
                    "camera_configuration",
                    mapOf(
                        "lensOptions" to emptyList<String>(),
                        "selectedLens" to cameraState.value.selectedPhysicalLens?.label,
                        "stabilizationSupported" to false,
                    ),
                )
                diagnosticEvent(
                    "root_encoder_prepared",
                    mapOf(
                        "codec" to configuration.codec.protocolId,
                        "width" to video.width,
                        "height" to video.height,
                        "fps" to video.fps,
                        "bitrateBps" to video.bitrateBps,
                    ),
                )
                Result.success(Unit)
            } catch (cause: Throwable) {
                diagnosticEvent(
                    "root_encoder_prepare_failed",
                    mapOf("reason" to cause.message, "failureType" to cause::class.simpleName),
                )
                runCatching { encoder?.release() }
                stream = null
                cameraSource = null
                cameraState.value = CameraInteractionState.inactive()
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
    ): Result<Unit> = cameraMutex.withLock {
        runCatching {
            val encoder = stream ?: error("Stream has not been prepared")
            encoder.startStream("udp://$receiverHost:$mediaPort")
            updateCameraStateLocked()
        }.recoverCatching { cause ->
            throw StreamFailureException(StreamFailure.StreamStartFailed(cause), cause)
        }
    }

    override suspend fun updateBitrate(bitrateBps: Int): Result<Unit> = cameraMutex.withLock {
        runCatching {
            check(bitrateBps > MINIMUM_BITRATE_BPS) { "Bitrate must be positive" }
            (stream ?: error("Stream has not been prepared")).setVideoBitrateOnFly(bitrateBps)
        }
    }

    override suspend fun stop(): Result<Unit> = cameraMutex.withLock {
        runCatching {
            stream?.let { encoder ->
                if (encoder.isStreaming) encoder.stopStream()
                if (encoder.isOnPreview) encoder.stopPreview()
            }
            diagnosticEvent("root_encoder_stopped")
            cameraState.value = CameraInteractionState.inactive()
        }
    }

    override suspend fun release() = cameraMutex.withLock {
        stream?.release()
        diagnosticEvent("root_encoder_released")
        stream = null
        cameraSource = null
        cameraState.value = CameraInteractionState.inactive()
        diagnosticRunId = null
        diagnosticSessionId = null
    }

    override suspend fun setPreviewSurface(surface: CameraPreviewSurface?) = cameraMutex.withLock {
        if (previewSurface == surface) return@withLock
        previewSurface = surface
        val encoder = stream ?: return@withLock
        if (surface == null) {
            if (encoder.isOnPreview) encoder.stopPreview()
            diagnosticEvent("preview_surface_detached")
            updateCameraStateLocked()
            return@withLock
        }
        if (encoder.isOnPreview) encoder.stopPreview()
        attachPreviewIfValidLocked(encoder, surface)
        diagnosticEvent("preview_surface_attached")
        updateCameraStateLocked()
    }

    override suspend fun setZoomRatio(zoomRatio: Float) = cameraMutex.withLock {
        val nextState = cameraState.value.withZoomRatio(zoomRatio)
        cameraState.value = nextState
        val source = cameraSource?.takeIf { it.isRunning() } ?: return@withLock
        source.setZoom(nextState.zoomRatio)
        diagnosticEvent("camera_zoom_changed", mapOf("zoomRatio" to nextState.zoomRatio))
        updateCameraStateLocked()
    }

    override suspend fun resetZoom() = cameraMutex.withLock {
        val nextState = cameraState.value.resetZoom()
        cameraState.value = nextState
        val source = cameraSource?.takeIf { it.isRunning() } ?: return@withLock
        source.setZoom(nextState.zoomRatio)
        diagnosticEvent("camera_zoom_reset", mapOf("zoomRatio" to nextState.zoomRatio))
        updateCameraStateLocked()
    }

    override suspend fun setStabilizationEnabled(enabled: Boolean) = cameraMutex.withLock {
        diagnosticEvent(
            "camera_stabilization_changed",
            mapOf(
                "requested" to enabled,
                "applied" to false,
                "reason" to "unsupported_by_rootencoder_camerax_source",
            ),
        )
    }

    override suspend fun selectPhysicalLens(lens: PhysicalLensOption) = cameraMutex.withLock {
        diagnosticEvent(
            "camera_lens_selection_failed",
            mapOf("lens" to lens.label, "reason" to "unsupported_by_rootencoder_camerax_source"),
        )
    }

    private fun attachPreviewLocked(
        encoder: UdpStream,
        target: CameraPreviewSurface,
    ) {
        val glInterface = encoder.getGlInterface()
        // Keep stream dimensions landscape so the negotiated receiver profile remains authoritative.
        glInterface.setCameraOrientation(target.orientation.cameraRotationDegrees)
        glInterface.setPreviewIsPortrait(target.orientation.isPortrait)
        glInterface.setPreviewResolution(target.width, target.height)
        encoder.startPreview(target.surface, target.width, target.height)
    }

    private fun attachPreviewIfValidLocked(
        encoder: UdpStream,
        target: CameraPreviewSurface,
    ) {
        if (!target.surface.isValid) {
            previewSurface = null
            return
        }
        runCatching { attachPreviewLocked(encoder, target) }
            .onFailure { cause ->
                previewSurface = null
                diagnosticEvent(
                    "preview_surface_attach_failed",
                    mapOf("reason" to cause.message),
                )
            }
    }

    private fun updateCameraStateLocked() {
        val source = cameraSource
        if (source == null || !source.isRunning()) {
            cameraState.value = CameraInteractionState.inactive()
            return
        }
        val range = source.getZoomRange()
        val currentZoom = source.getZoom().takeUnless { it <= NO_ZOOM_REPORTED }
            ?: CameraZoom.DEFAULT_ZOOM_RATIO
        cameraState.value = cameraState.value.withCameraBounds(
            minimum = range.lower,
            maximum = range.upper,
            current = currentZoom,
        ).withPhysicalLensOptions(emptyList())
            .withStabilizationSupport(supported = false)
    }

    private fun diagnosticEvent(name: String, fields: Map<String, Any?> = emptyMap()) {
        val context = mapOf(
            "runId" to diagnosticRunId,
            "sessionId" to diagnosticSessionId,
        )
        logger.event(name, (fields + context).filterValues { it != null })
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 32
        const val AUDIO_SAMPLE_RATE_HZ = 44_100
        const val AUDIO_BITRATE_BPS = 64_000
        const val MINIMUM_BITRATE_BPS = 0
        const val NO_ZOOM_REPORTED = 0.0f

    }
}
