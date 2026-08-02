package dev.mobilewebcam.sender.streaming.rootencoder

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import com.pedro.encoder.input.sources.audio.NoAudioSource
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.udp.UdpStream
import dev.mobilewebcam.sender.camera.CameraController
import dev.mobilewebcam.sender.camera.CameraInteractionState
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.camera.CameraStabilizationMode
import dev.mobilewebcam.sender.camera.CameraStabilizationSupport
import dev.mobilewebcam.sender.camera.PhysicalLensOption
import dev.mobilewebcam.sender.camera.physicalLensOptionsFor
import dev.mobilewebcam.sender.camera.preferredStabilizationMode
import dev.mobilewebcam.sender.config.CameraZoom
import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.streaming.StreamEngine
import dev.mobilewebcam.sender.streaming.StreamEngineEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RootEncoderStreamEngine(context: Context) : StreamEngine, CameraController {
    private val applicationContext = context.applicationContext
    private val cameraManager = applicationContext
        .getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val eventFlow = MutableSharedFlow<StreamEngineEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )
    private val cameraMutex = Mutex()
    private val cameraState = MutableStateFlow(CameraInteractionState())
    private var stream: UdpStream? = null
    private var cameraSource: Camera2Source? = null
    private var previewSurface: CameraPreviewSurface? = null
    private var physicalLensOptions = emptyList<PhysicalLensOption>()
    private var stabilizationSupport = unsupportedStabilization()

    override val events: Flow<StreamEngineEvent> = eventFlow
    override val state: StateFlow<CameraInteractionState> = cameraState.asStateFlow()

    override suspend fun prepare(configuration: StreamConfiguration): Result<Unit> =
        cameraMutex.withLock {
            var encoder: UdpStream? = null
            return@withLock try {
                check(stream == null) { "A stream is already prepared" }
                val source = Camera2Source(applicationContext)
                val createdEncoder = UdpStream(
                    applicationContext,
                    RootEncoderEventAdapter(eventFlow),
                    source,
                    NoAudioSource(),
                )
                encoder = createdEncoder
                cameraSource = source
                physicalLensOptions = discoverPhysicalLensOptions(source)
                stabilizationSupport = discoverStabilizationSupport(source)
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
                Result.success(Unit)
            } catch (cause: Throwable) {
                runCatching { encoder?.release() }
                stream = null
                cameraSource = null
                physicalLensOptions = emptyList()
                stabilizationSupport = unsupportedStabilization()
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
            cameraState.value = CameraInteractionState.inactive()
        }
    }

    override suspend fun release() = cameraMutex.withLock {
            stream?.release()
            stream = null
            cameraSource = null
            physicalLensOptions = emptyList()
            stabilizationSupport = unsupportedStabilization()
            cameraState.value = CameraInteractionState.inactive()
    }

    override suspend fun setPreviewSurface(surface: CameraPreviewSurface?) = cameraMutex.withLock {
        if (previewSurface == surface) return@withLock
        previewSurface = surface
        val encoder = stream ?: return@withLock
        if (surface == null) {
            if (encoder.isOnPreview) encoder.stopPreview()
            updateCameraStateLocked()
            return@withLock
        }
        if (encoder.isOnPreview) encoder.stopPreview()
        attachPreviewIfValidLocked(encoder, surface)
        updateCameraStateLocked()
    }

    override suspend fun setZoomRatio(zoomRatio: Float) = cameraMutex.withLock {
        val nextState = cameraState.value.withZoomRatio(zoomRatio)
        cameraState.value = nextState
        val source = cameraSource?.takeIf { it.isRunning() } ?: return@withLock
        source.setZoom(nextState.zoomRatio)
        updateCameraStateLocked()
    }

    override suspend fun resetZoom() = cameraMutex.withLock {
        val nextState = cameraState.value.resetZoom()
        cameraState.value = nextState
        val source = cameraSource?.takeIf { it.isRunning() } ?: return@withLock
        source.setZoom(nextState.zoomRatio)
        updateCameraStateLocked()
    }

    override suspend fun setStabilizationEnabled(enabled: Boolean) = cameraMutex.withLock {
        val source = cameraSource?.takeIf { it.isRunning() } ?: return@withLock
        if (enabled && !stabilizationSupport.isSupported) return@withLock
        val applied = runCatching {
            applyStabilization(source, enabled)
        }.getOrDefault(false)
        if (applied) {
            cameraState.value = cameraState.value.withStabilizationEnabled(enabled)
        }
    }

    override suspend fun selectPhysicalLens(lens: PhysicalLensOption) = cameraMutex.withLock {
        if (lens !in physicalLensOptions) return@withLock
        val nextState = cameraState.value.withSelectedPhysicalLens(lens)
        cameraState.value = nextState
        val source = cameraSource?.takeIf { it.isRunning() } ?: return@withLock
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return@withLock
        runCatching { source.openPhysicalCamera(lens.cameraId) }
            .onSuccess { updateCameraStateLocked() }
    }

    private fun attachPreviewLocked(
        encoder: UdpStream,
        target: CameraPreviewSurface,
    ) {
        val glInterface = encoder.getGlInterface()
        // Camera2Source uses the sensor/display orientation supplied to the
        // RootEncoder GL renderer. Keep stream dimensions landscape so the
        // negotiated receiver profile remains authoritative.
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
            .onFailure { previewSurface = null }
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
        ).withPhysicalLensOptions(physicalLensOptions)
            .withStabilizationSupport(stabilizationSupport.isSupported)
    }

    private fun discoverPhysicalLensOptions(source: Camera2Source): List<PhysicalLensOption> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        return runCatching { physicalLensOptionsFor(source.physicalCamerasAvailable()) }
            .getOrDefault(emptyList())
    }

    private fun discoverStabilizationSupport(source: Camera2Source): CameraStabilizationSupport {
        return runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(source.getCurrentCameraId())
            CameraStabilizationSupport(
                opticalSupported = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true,
                electronicSupported = characteristics
                    .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true,
            )
        }.getOrDefault(unsupportedStabilization())
    }

    private fun applyStabilization(
        source: Camera2Source,
        enabled: Boolean,
    ): Boolean {
        if (!enabled) {
            source.disableOpticalVideoStabilization()
            source.disableVideoStabilization()
            return true
        }

        return when (preferredStabilizationMode(stabilizationSupport)) {
            CameraStabilizationMode.OPTICAL -> {
                source.disableVideoStabilization()
                if (source.enableOpticalVideoStabilization()) {
                    true
                } else {
                    applyElectronicStabilization(source)
                }
            }
            CameraStabilizationMode.ELECTRONIC -> applyElectronicStabilization(source)
            null -> false
        }
    }

    private fun applyElectronicStabilization(source: Camera2Source): Boolean {
        source.disableOpticalVideoStabilization()
        return source.enableVideoStabilization()
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 32
        const val AUDIO_SAMPLE_RATE_HZ = 44_100
        const val AUDIO_BITRATE_BPS = 64_000
        const val MINIMUM_BITRATE_BPS = 0
        const val NO_ZOOM_REPORTED = 0.0f

        fun unsupportedStabilization() = CameraStabilizationSupport(
            opticalSupported = false,
            electronicSupported = false,
        )
    }
}
