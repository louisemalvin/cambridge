package dev.mobilewebcam.sender.media.streaming.rootencoder

import android.content.Context
import android.os.Build
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.srt.SrtStream
import com.pedro.srt.srt.packets.control.handshake.EncryptionType
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.media.camera.CameraZoom
import dev.mobilewebcam.sender.media.camera.PhysicalLensOption
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.model.SrtTransportEndpoint
import dev.mobilewebcam.sender.media.streaming.StreamEngine
import dev.mobilewebcam.sender.media.streaming.StreamEngineEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RootEncoderStreamEngine(
    context: Context,
    private val logger: AppLogger = AndroidAppLogger,
) : StreamEngine, CameraController {
    private val applicationContext = context.applicationContext
    private val cameraSourceFactory = RootEncoderCameraSourceFactory(applicationContext)
    private val eventFlow = MutableSharedFlow<StreamEngineEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )
    private val cameraMutex = Mutex()
    private val cameraState = MutableStateFlow(CameraInteractionState())
    private var stream: SrtStream? = null
    private var cameraSource: Camera2Source? = null
    private var cameraDescriptors: List<RootEncoderCameraDescriptor> = emptyList()
    private var selectedLensId: String? = null
    private var activeCameraDescriptor: RootEncoderCameraDescriptor? = null
    private var previewSurface: CameraPreviewSurface? = null
    private var diagnosticRunId: String? = null
    private var diagnosticSessionId: String? = null

    override val events: Flow<StreamEngineEvent> = eventFlow
    override val state: StateFlow<CameraInteractionState> = cameraState.asStateFlow()

    override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> =
        cameraMutex.withLock {
            var encoder: SrtStream? = null
            return@withLock try {
                check(stream == null) { "A stream is already prepared" }
                diagnosticRunId = configuration.runId
                diagnosticSessionId = configuration.sessionId
                val source = cameraSourceFactory.createCameraSource()
                val createdEncoder = SrtStream(
                    applicationContext,
                    RootEncoderEventAdapter(eventFlow),
                    source,
                    NoAudioSource(),
                )
                encoder = createdEncoder
                cameraSource = source
                cameraDescriptors = cameraSourceFactory.availableCameraDescriptors()
                selectedLensId = null
                activeCameraDescriptor = null
                stream = createdEncoder
                val video = configuration.toRootEncoderVideo()
                createdEncoder.setVideoCodec(configuration.codec.toRootEncoder())
                createdEncoder.getStreamClient().setOnlyVideo(true)
                check(withContext(Dispatchers.Main.immediate) {
                    createdEncoder.prepareVideo(
                        video.width,
                        video.height,
                        video.bitrateBps,
                        video.fps,
                        video.keyframeIntervalSeconds,
                        video.rotationDegrees,
                    )
                }) { "RootEncoder video preparation failed" }
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
                        "lensOptions" to cameraDescriptors.map { it.label },
                        "selectedLens" to cameraDescriptors.firstOrNull { it.selectionId == null }?.label,
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
                runCatching { stopEncoderOnMain(encoder) }
                stream = null
                cameraSource = null
                cameraDescriptors = emptyList()
                selectedLensId = null
                activeCameraDescriptor = null
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
        endpoint: SrtTransportEndpoint,
    ): Result<Unit> = cameraMutex.withLock {
        runCatching {
            val encoder = stream ?: error("Stream has not been prepared")
            val client = encoder.getStreamClient()
            client.setLatency(endpoint.rootEncoderLatencyMs())
            client.setPassphrase(endpoint.passphrase, EncryptionType.AES256)
            client.setReTries(DEFAULT_SRT_RETRY_ATTEMPTS)
            client.setCheckServerAlive(true)
            withContext(Dispatchers.Main.immediate) {
                encoder.startStream(endpoint.toRootEncoderUri())
            }
            activeCameraDescriptor = cameraDescriptors.firstOrNull { descriptor ->
                descriptor.physicalCameraId == null && descriptor.selectionId == null
            }
            updateCameraStateLocked()
            diagnosticEvent(
                "camera_configuration",
                mapOf(
                    "lensOptions" to cameraState.value.physicalLensOptions.map { it.label },
                    "selectedLens" to cameraState.value.selectedPhysicalLens?.label,
                    "stabilizationSupported" to false,
                ),
            )
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
            releaseLocked("root_encoder_stopped")
        }
    }

    override suspend fun release() = cameraMutex.withLock {
        releaseLocked("root_encoder_released")
    }

    override suspend fun setPreviewSurface(surface: CameraPreviewSurface?) = cameraMutex.withLock {
        if (previewSurface == surface) return@withLock
        previewSurface = surface
        val encoder = stream ?: return@withLock
        if (surface == null) {
            withContext(Dispatchers.Main.immediate) {
                if (encoder.isOnPreview) encoder.stopPreview()
            }
            diagnosticEvent("preview_surface_detached")
            updateCameraStateLocked()
            return@withLock
        }
        withContext(Dispatchers.Main.immediate) {
            if (encoder.isOnPreview) encoder.stopPreview()
        }
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
                "reason" to "unsupported_by_rootencoder_camera2_source",
            ),
        )
    }

    override suspend fun selectPhysicalLens(lens: PhysicalLensOption) = cameraMutex.withLock {
        val source = cameraSource?.takeIf { it.isRunning() } ?: return@withLock
        val descriptor = if (lens.cameraId == null) {
            cameraDescriptors.firstOrNull { it.selectionId == null }
        } else {
            cameraDescriptors.firstOrNull { it.selectionId == lens.cameraId }
        }
        if (descriptor == null) {
            diagnosticEvent(
                "camera_lens_selection_failed",
                mapOf("lens" to lens.label, "reason" to "camera_info_unavailable"),
            )
            return@withLock
        }

        runCatching {
            withContext(Dispatchers.Main.immediate) {
                rebindCamera(source, descriptor)
            }
        }.onSuccess {
            activeCameraDescriptor = descriptor
            selectedLensId = lens.cameraId
            updateCameraStateLocked()
            diagnosticEvent(
                "camera_lens_selected",
                mapOf(
                    "lens" to lens.label,
                    "selectionId" to lens.cameraId,
                    "logicalCameraId" to descriptor.logicalCameraId,
                    "physicalCameraId" to descriptor.physicalCameraId,
                ),
            )
        }.onFailure { cause ->
            diagnosticEvent(
                "camera_lens_selection_failed",
                mapOf(
                    "lens" to lens.label,
                    "selectionId" to lens.cameraId,
                    "logicalCameraId" to descriptor.logicalCameraId,
                    "physicalCameraId" to descriptor.physicalCameraId,
                    "reason" to cause.message,
                ),
            )
        }
    }

    private fun rebindCamera(
        source: Camera2Source,
        descriptor: RootEncoderCameraDescriptor,
    ) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P || descriptor.physicalCameraId == null) {
            "Physical camera selection requires Android 9 or newer"
        }
        val current = activeCameraDescriptor
        val logicalCameraChanged = current?.logicalCameraId != descriptor.logicalCameraId
        val physicalCameraChanged = current?.physicalCameraId != descriptor.physicalCameraId
        if (logicalCameraChanged) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                source.openPhysicalCamera(null)
            }
            source.openCameraId(descriptor.logicalCameraId)
        }
        val physicalCameraNeedsRebind = physicalCameraChanged &&
            (descriptor.physicalCameraId != null || !logicalCameraChanged)
        if (physicalCameraNeedsRebind && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            source.openPhysicalCamera(descriptor.physicalCameraId)
        }
    }

    private fun attachPreviewLocked(
        encoder: SrtStream,
        target: CameraPreviewSurface,
    ) {
        val glInterface = encoder.getGlInterface()
        // Keep stream dimensions landscape so the negotiated receiver profile remains authoritative.
        glInterface.setCameraOrientation(target.orientation.cameraRotationDegrees)
        glInterface.setPreviewIsPortrait(target.orientation.isPortrait)
        glInterface.setPreviewResolution(target.width, target.height)
        encoder.startPreview(target.surface, target.width, target.height)
    }

    private suspend fun attachPreviewIfValidLocked(
        encoder: SrtStream,
        target: CameraPreviewSurface,
    ) {
        if (!target.surface.isValid) {
            previewSurface = null
            return
        }
        withContext(Dispatchers.Main.immediate) {
            runCatching { attachPreviewLocked(encoder, target) }
                .onFailure { cause ->
                    previewSurface = null
                    diagnosticEvent(
                        "preview_surface_attach_failed",
                        mapOf("reason" to cause.message),
                    )
                }
        }
    }

    private suspend fun stopEncoderOnMain(encoder: SrtStream?) {
        withContext(Dispatchers.Main.immediate) {
            encoder ?: return@withContext
            when {
                encoder.isStreaming -> {
                    encoder.stopStream()
                    if (encoder.isOnPreview) {
                        encoder.stopPreview()
                    }
                    encoder.videoSource.release()
                    encoder.audioSource.release()
                }
                encoder.isOnPreview -> {
                    encoder.stopPreview()
                    encoder.audioSource.stop()
                    encoder.videoSource.release()
                    encoder.audioSource.release()
                }
                else -> encoder.release()
            }
        }
    }

    private suspend fun releaseLocked(eventName: String) {
        if (stream != null) {
            stopEncoderOnMain(stream)
            diagnosticEvent(eventName)
        }
        stream = null
        cameraSource = null
        cameraDescriptors = emptyList()
        selectedLensId = null
        activeCameraDescriptor = null
        cameraState.value = CameraInteractionState.inactive()
        diagnosticRunId = null
        diagnosticSessionId = null
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
        val lensOptions = cameraDescriptors.map { descriptor ->
            PhysicalLensOption(
                label = descriptor.label,
                cameraId = descriptor.selectionId,
            )
        }
        val nextState = cameraState.value.withCameraBounds(
            minimum = range.lower,
            maximum = range.upper,
            current = currentZoom,
        ).withPhysicalLensOptions(lensOptions)
            .withStabilizationSupport(supported = false)
        val selectedLens = lensOptions.firstOrNull { it.cameraId == selectedLensId }
            ?: lensOptions.firstOrNull()
        cameraState.value = selectedLens?.let(nextState::withSelectedPhysicalLens) ?: nextState
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
        const val DEFAULT_SRT_RETRY_ATTEMPTS = 6

    }
}
