package dev.cambridge.sender.media.streaming.cambridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.cambridge.sender.logging.AndroidAppLogger
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraPermissionRequiredException
import dev.cambridge.sender.media.camera.CameraPreviewSurface
import dev.cambridge.sender.media.camera.CameraZoom
import dev.cambridge.sender.media.camera.AppliedVideoStabilizationMode
import dev.cambridge.sender.media.camera.CameraStabilizationApplyStatus
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.media.camera.CameraStabilizationObservation
import dev.cambridge.sender.media.camera.CameraStabilizationReducer
import dev.cambridge.sender.media.camera.CameraStabilizationState
import dev.cambridge.sender.media.camera.RequestedVideoStabilizationMode
import dev.cambridge.sender.media.camera.PhysicalLensOption
import dev.cambridge.sender.media.camera.CameraLensFacing
import dev.cambridge.sender.media.camera.SessionTransform
import dev.cambridge.sender.media.camera.toDisplayOrientation
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.VideoProfile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.Executor
import kotlin.math.abs

private data class StabilizationRequest(
    val videoMode: Int,
    val opticalMode: Int,
)

internal class Camera2Capture(
    context: Context,
    private val logger: AppLogger = AndroidAppLogger,
) {
    private val applicationContext = context.applicationContext
    private val cameraManager = applicationContext.getSystemService(CameraManager::class.java)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var encoderSurface: Surface? = null
    private var requestedFps: Int? = null
    private var requestedWidth: Int? = null
    private var requestedHeight: Int? = null
    private var previewSurface: CameraPreviewSurface? = null
    private var selectedCameraId: String? = null
    private var selectedBackCameraId: String? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var zoomRatio = CameraZoom.DEFAULT_ZOOM_RATIO
    private var requestedStabilizationPreference = CameraStabilizationMode.OFF
    private var diagnosticsRunId: String? = null
    private var diagnosticsSessionId: String? = null
    private var requestedAntiFlickerMode = AntiFlickerMode.AUTO
    private var loggedFirstCapture = false
    private var captureResultCount = 0L
    private var captureSummaryStartNs = 0L
    private var captureSummaryStartCount = 0L
    private var lastSensorTimestampNs = 0L

    val cameraState = kotlinx.coroutines.flow.MutableStateFlow(CameraInteractionState())

    suspend fun prepare() {
        requireCameraPermission("Camera permission is required before preparing the CamBridge sender")
        val cameraId = selectedCameraId ?: selectDefaultCameraId().also { selectedCameraId = it }
        rememberBackCamera(cameraId)
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        updateCameraState()
    }

    fun setDiagnosticsContext(runId: String?, sessionId: String?) {
        diagnosticsRunId = runId
        diagnosticsSessionId = sessionId
    }

    suspend fun snapshotSessionTransform(
        codedWidth: Int,
        codedHeight: Int,
        orientation: StreamOrientation,
    ): SessionTransform {
        requireCameraPermission("Camera permission is required before selecting video quality")
        if (cameraCharacteristics == null) {
            startThread()
            val cameraId = selectDefaultCameraId()
            selectedCameraId = cameraId
            rememberBackCamera(cameraId)
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            updateCameraState()
        }
        val characteristics = cameraCharacteristics ?: error("Camera characteristics are unavailable")
        return SessionTransform.calculate(
            displayOrientation = orientation.toDisplayOrientation(),
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: DEFAULT_SENSOR_ORIENTATION_DEGREES,
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING).toLensFacing(),
            codedWidth = codedWidth,
            codedHeight = codedHeight,
        )
    }

    suspend fun supportedVideoModes(modes: List<VideoProfile>): Set<String> {
        if (cameraCharacteristics == null) prepare()
        val characteristics = cameraCharacteristics ?: return emptySet()
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptySet()
        val supportedSizes = map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { size -> size.width to size.height }
            ?.toSet()
            .orEmpty()
        val supportedFpsRanges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        return modes.filter { mode ->
            val outputSize = when {
                (mode.width to mode.height) in supportedSizes -> Size(mode.width, mode.height)
                (mode.height to mode.width) in supportedSizes -> Size(mode.height, mode.width)
                else -> null
            }
            val fpsSupported = supportedFpsRanges.any { range ->
                range.lower == mode.fps && range.upper == mode.fps ||
                    mode.fps in range.lower..range.upper
            }
            val frameDurationSupported = outputSize?.let { size ->
                val minimumFrameDurationNanos = map.getOutputMinFrameDuration(
                    SurfaceTexture::class.java,
                    size,
                )
                minimumFrameDurationNanos <= NANOSECONDS_PER_SECOND / mode.fps
            } == true
            outputSize != null && fpsSupported && frameDurationSupported
        }.mapTo(linkedSetOf()) { mode -> mode.id }
    }

    suspend fun start(
        surface: Surface,
        targetFps: Int,
        codedWidth: Int? = null,
        codedHeight: Int? = null,
    ) {
        requireCameraPermission("Camera permission is required before starting the CamBridge sender")
        require(targetFps in CamBridgeStreamContract.MINIMUM_FPS..CamBridgeStreamContract.MAXIMUM_FPS) {
            "Requested frame rate is outside the CamBridge stream contract"
        }
        requestedFps = targetFps
        requestedWidth = codedWidth ?: requestedWidth
        requestedHeight = codedHeight ?: requestedHeight
        encoderSurface = surface
        startThread()
        val cameraId = selectedCameraId ?: selectDefaultCameraId().also { selectedCameraId = it }
        rememberBackCamera(cameraId)
        val device = openCamera(cameraId)
        cameraDevice = device
        beginStabilizationApplication()
        configureCaptureSession(device)
    }

    suspend fun stop() {
        closeCamera()
        encoderSurface = null
        requestedFps = null
        requestedWidth = null
        requestedHeight = null
        previewSurface = null
        diagnosticsRunId = null
        diagnosticsSessionId = null
        loggedFirstCapture = false
        captureResultCount = 0L
        captureSummaryStartNs = 0L
        captureSummaryStartCount = 0L
        lastSensorTimestampNs = 0L
        cameraState.value = CameraInteractionState.inactive()
        stopThread()
    }

    suspend fun setPreviewSurface(surface: CameraPreviewSurface?) {
        val nextSurface = surface?.takeIf { it.surface.isValid }
        if (previewSurface?.surface === nextSurface?.surface) return
        previewSurface = nextSurface
        if (cameraDevice != null && encoderSurface != null) {
            captureSession?.close()
            captureSession = null
            beginStabilizationApplication()
            configureCaptureSession(cameraDevice ?: return)
        }
    }

    suspend fun setZoomRatio(requestedRatio: Float) {
        zoomRatio = cameraState.value.withZoomRatio(requestedRatio).zoomRatio
        submitRepeatingRequest()
        updateCameraState()
    }

    suspend fun resetZoom() {
        setZoomRatio(CameraZoom.DEFAULT_ZOOM_RATIO)
    }

    suspend fun toggleCameraFacing() {
        val currentFacing = cameraCharacteristics
            ?.get(CameraCharacteristics.LENS_FACING)
            .toLensFacing()
        val requestedFacing = if (currentFacing == CameraLensFacing.FRONT) {
            CameraLensFacing.BACK
        } else {
            CameraLensFacing.FRONT
        }
        val requestedId = when (requestedFacing) {
            CameraLensFacing.BACK -> selectedBackCameraId
                ?.takeIf { cameraFacing(it) == CameraLensFacing.BACK }
                ?: firstCameraId(CameraLensFacing.BACK)
            CameraLensFacing.FRONT -> firstCameraId(CameraLensFacing.FRONT)
            CameraLensFacing.EXTERNAL,
            CameraLensFacing.UNKNOWN,
            -> null
        } ?: return
        switchCamera(requestedId)
    }

    suspend fun setStabilizationMode(mode: CameraStabilizationMode) {
        if (mode == requestedStabilizationPreference &&
            cameraState.value.stabilization.selectedMode == mode
        ) return
        requestedStabilizationPreference = mode
        val supportedModes = availableStabilizationModes()
        val supportedState = CameraStabilizationReducer.withSupportedModes(
            current = cameraState.value.stabilization,
            supportedModes = supportedModes,
            persistedPreference = mode,
        )
        val effectiveMode = mode.takeIf { it in supportedModes } ?: CameraStabilizationMode.OFF
        val captureSessionActive = cameraDevice != null && encoderSurface != null
        val requestedState = if (!captureSessionActive ||
            (effectiveMode == CameraStabilizationMode.OFF && mode != effectiveMode)
        ) {
            supportedState
        } else {
            CameraStabilizationReducer.request(
                current = supportedState,
                requestedMode = effectiveMode,
                nowMillis = SystemClock.elapsedRealtime(),
            )
        }
        cameraState.value = cameraState.value.withStabilizationState(requestedState)
        loggerStabilizationRequest(mode)
        if (captureSessionActive) {
            if (usesStabilizationSessionParameters()) {
                captureSession?.close()
                captureSession = null
                configureCaptureSession(cameraDevice ?: return)
            } else {
                submitRepeatingRequest()
            }
        }
    }

    suspend fun setAntiFlickerMode(mode: AntiFlickerMode) {
        val supportedModes = availableAntiFlickerModes()
        require(mode in supportedModes) {
            "Anti-flicker mode is not supported by the selected camera"
        }
        requestedAntiFlickerMode = mode
        cameraState.value = cameraState.value
            .withAntiFlickerSupport(supportedModes)
            .withAntiFlickerMode(mode)
        submitRepeatingRequest()
    }

    suspend fun selectPhysicalLens(lens: PhysicalLensOption) {
        val requestedId = lens.cameraId ?: return
        require(cameraFacing(requestedId) == CameraLensFacing.BACK) {
            "Physical lens selection is limited to rear cameras"
        }
        selectedBackCameraId = requestedId
        switchCamera(requestedId)
    }

    private suspend fun switchCamera(requestedId: String) {
        if (requestedId == selectedCameraId) return
        val wasRunning = cameraDevice != null
        if (wasRunning) closeCamera()
        selectedCameraId = requestedId
        rememberBackCamera(requestedId)
        cameraCharacteristics = cameraManager.getCameraCharacteristics(requestedId)
        val supportedModes = availableStabilizationModes()
        cameraState.value = cameraState.value.withStabilizationState(
            CameraStabilizationReducer.withSupportedModes(
                current = cameraState.value.stabilization,
                supportedModes = supportedModes,
                persistedPreference = requestedStabilizationPreference,
            ),
        )
        updateCameraState()
        if (wasRunning) {
            val surface = encoderSurface ?: return
            start(
                surface,
                targetFps = requestedFps
                    ?: error("Requested frame rate is unavailable while switching lenses"),
                codedWidth = requestedWidth,
                codedHeight = requestedHeight,
            )
        }
    }

    private fun startThread() {
        if (cameraThread != null) return
        val thread = HandlerThread(CAMERA_THREAD_NAME)
        thread.start()
        cameraThread = thread
        cameraHandler = Handler(thread.looper)
    }

    private suspend fun stopThread() {
        cameraHandler = null
        val thread = cameraThread
        thread?.quitSafely()
        thread?.join(CAMERA_THREAD_JOIN_TIMEOUT_MILLIS)
        if (thread?.isAlive == true) {
            logger.event("camera_thread_join_timeout")
        }
        cameraThread = null
    }

    private fun selectDefaultCameraId(): String {
        val ids = cameraManager.cameraIdList.toList()
        return firstCameraId(CameraLensFacing.BACK)
            ?: ids.firstOrNull()
            ?: error("No Android camera is available")
    }

    private fun firstCameraId(facing: CameraLensFacing): String? =
        cameraManager.cameraIdList.firstOrNull { cameraFacing(it) == facing }

    private fun cameraFacing(cameraId: String): CameraLensFacing =
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.LENS_FACING)
            .toLensFacing()

    private fun rememberBackCamera(cameraId: String) {
        if (cameraFacing(cameraId) == CameraLensFacing.BACK) {
            selectedBackCameraId = cameraId
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        val manager = cameraManager
        val handler = cameraHandler ?: run {
            continuation.resumeWithException(IllegalStateException("Camera handler is not running"))
            return@suspendCancellableCoroutine
        }
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        continuation.resume(camera)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Camera disconnected while opening"))
                        }
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("Camera open failed: $error"))
                        }
                    }
                },
                handler,
            )
        } catch (security: SecurityException) {
            continuation.resumeWithException(
                CameraPermissionRequiredException(
                    "Camera permission is required before opening the camera",
                    security,
                ),
            )
        }
        continuation.invokeOnCancellation { cameraDevice?.close() }
    }

    private fun requireCameraPermission(message: String) {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw CameraPermissionRequiredException(message)
        }
    }

    private suspend fun configureCaptureSession(device: CameraDevice) = suspendCancellableCoroutine<Unit> { continuation ->
        val encoder = encoderSurface ?: run {
            continuation.resumeWithException(IllegalStateException("Encoder surface is unavailable"))
            return@suspendCancellableCoroutine
        }
        val outputs = buildList {
            add(encoder)
            previewSurface?.surface?.takeIf(Surface::isValid)?.let(::add)
        }.distinct()
        val handler = cameraHandler ?: run {
            continuation.resumeWithException(IllegalStateException("Camera handler is not running"))
            return@suspendCancellableCoroutine
        }
        var configuredSession: CameraCaptureSession? = null
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!continuation.isActive) {
                    session.close()
                    return
                }
                configuredSession = session
                captureSession = session
                runCatching { submitRepeatingRequest() }
                    .onSuccess {
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        } else {
                            if (captureSession === session) captureSession = null
                            session.close()
                        }
                    }
                    .onFailure { cause ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(cause)
                        } else {
                            if (captureSession === session) captureSession = null
                            session.close()
                        }
                    }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException("Camera capture session configuration failed"),
                    )
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val sessionConfiguration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs.map { surface -> OutputConfiguration(surface) },
                Executor { command -> handler.post(command) },
                callback,
            )
            sessionConfiguration.setSessionParameters(
                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    applyStabilizationSettings(this, sessionParametersOnly = true)
                }.build(),
            )
            device.createCaptureSession(sessionConfiguration)
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(outputs, callback, handler)
        }
        continuation.invokeOnCancellation { configuredSession?.close() }
    }

    private fun submitRepeatingRequest() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val encoder = encoderSurface ?: return
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encoder)
            previewSurface?.surface?.takeIf(Surface::isValid)?.let(::addTarget)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            targetFpsRange()?.let { range -> set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range) }
            antiFlickerCaptureRequestMode()?.let { mode ->
                set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, mode)
            }
            cropRegion()?.let { crop -> set(CaptureRequest.SCALER_CROP_REGION, crop) }
            applyStabilizationSettings(this)
        }
        session.setRepeatingRequest(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val nowNs = System.nanoTime()
                    observeStabilizationResult(result)
                    val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                    val previousSensorTimestampNs = lastSensorTimestampNs
                    captureResultCount += 1L
                    lastSensorTimestampNs = sensorTimestampNs
                    if (!loggedFirstCapture) {
                        loggedFirstCapture = true
                        logger.event(
                            "camera_first_capture_result",
                            mapOf(
                                "sensorTimestampNs" to sensorTimestampNs,
                                "antiFlickerMode" to cameraState.value.antiFlickerMode.name,
                            ),
                        )
                    }
                    if (captureSummaryStartNs == 0L) {
                        captureSummaryStartNs = nowNs
                        captureSummaryStartCount = captureResultCount
                    } else if (nowNs - captureSummaryStartNs >= CAPTURE_SUMMARY_INTERVAL_NS) {
                        val elapsedNs = nowNs - captureSummaryStartNs
                        val intervalFrames = captureResultCount - captureSummaryStartCount
                        logger.event(
                            "camera_capture_summary",
                            mapOf(
                                "frames" to captureResultCount,
                                "intervalFrames" to intervalFrames,
                                "intervalFps" to intervalFrames.toDouble() * NANOSECONDS_PER_SECOND / elapsedNs,
                                "sensorTimestampNs" to sensorTimestampNs,
                                "sensorTimestampDeltaNs" to if (previousSensorTimestampNs == 0L) {
                                    0L
                                } else {
                                    sensorTimestampNs - previousSensorTimestampNs
                                },
                            ),
                        )
                        captureSummaryStartNs = nowNs
                        captureSummaryStartCount = captureResultCount
                    }
                }
            },
            cameraHandler,
        )
    }

    private fun cropRegion(): Rect? {
        val characteristics = cameraCharacteristics ?: return null
        val activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        if (zoomRatio <= CameraZoom.DEFAULT_ZOOM_RATIO) return activeArray
        val centerX = activeArray.centerX()
        val centerY = activeArray.centerY()
        val width = (activeArray.width() / zoomRatio).toInt().coerceAtLeast(MINIMUM_CROP_DIMENSION)
        val height = (activeArray.height() / zoomRatio).toInt().coerceAtLeast(MINIMUM_CROP_DIMENSION)
        return Rect(
            centerX - width / CROP_CENTER_DIVISOR,
            centerY - height / CROP_CENTER_DIVISOR,
            centerX + width / CROP_CENTER_DIVISOR,
            centerY + height / CROP_CENTER_DIVISOR,
        )
    }

    private fun targetFpsRange(): Range<Int>? {
        val target = requestedFps ?: return null
        val ranges = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        if (ranges.isEmpty()) return null
        return ranges.firstOrNull { range -> range.lower == target && range.upper == target }
            ?: ranges.filter { range -> target in range.lower..range.upper }
                .minWithOrNull(
                    compareBy<Range<Int>> { range -> range.upper - range.lower }
                        .thenByDescending { range -> range.lower },
                )
            ?: error("Camera does not support the requested target frame rate: $target")
    }

    private fun availableStabilizationModes(): List<CameraStabilizationMode> {
        val characteristics = cameraCharacteristics ?: return emptyList()
        val videoModes = characteristics
            .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toSet()
            .orEmpty()
        val opticalModes = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toSet()
            .orEmpty()
        return buildList {
            if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in opticalModes) {
                add(CameraStabilizationMode.OPTICAL)
            }
            if (CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON in videoModes) {
                add(CameraStabilizationMode.ELECTRONIC)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in videoModes
            ) {
                add(CameraStabilizationMode.PREVIEW)
            }
        }
    }

    private fun effectiveStabilizationMode(): CameraStabilizationMode =
        cameraState.value.stabilization.selectedMode

    private fun beginStabilizationApplication() {
        val state = cameraState.value.stabilization
        if (state.applyStatus == CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM &&
            state.requestedMode != CameraStabilizationMode.OFF
        ) return
        if (state.selectedMode !in state.supportedModes) return
        cameraState.value = cameraState.value.withStabilizationState(
            CameraStabilizationReducer.request(
                current = state,
                requestedMode = state.selectedMode,
                nowMillis = SystemClock.elapsedRealtime(),
            ),
        )
    }

    private fun applyStabilizationSettings(
        builder: CaptureRequest.Builder,
        sessionParametersOnly: Boolean = false,
    ) {
        val mode = effectiveStabilizationMode()
        val videoModes = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.toSet()
            .orEmpty()
        val opticalModes = cameraCharacteristics
            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.toSet()
            .orEmpty()
        val sessionKeys = if (sessionParametersOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cameraCharacteristics?.getAvailableSessionKeys()?.toSet().orEmpty()
        } else {
            emptySet()
        }
        val request = stabilizationRequest(mode)
        if (request.videoMode in videoModes &&
            (!sessionParametersOnly || CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in sessionKeys)
        ) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, request.videoMode)
        }
        if (request.opticalMode in opticalModes &&
            (!sessionParametersOnly || CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in sessionKeys)
        ) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, request.opticalMode)
        }
    }

    private fun stabilizationRequest(mode: CameraStabilizationMode): StabilizationRequest {
        val request = CameraStabilizationReducer.requestFor(mode)
        return StabilizationRequest(
            videoMode = when (request.videoMode) {
                RequestedVideoStabilizationMode.OFF -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                RequestedVideoStabilizationMode.ELECTRONIC -> CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                RequestedVideoStabilizationMode.PREVIEW ->
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            },
            opticalMode = if (request.opticalOn) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            },
        )
    }

    private fun usesStabilizationSessionParameters(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val keys = cameraCharacteristics?.getAvailableSessionKeys()?.toSet().orEmpty()
        return CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in keys ||
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE in keys
    }

    private fun observeStabilizationResult(result: TotalCaptureResult) {
        val videoMode = when (result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)) {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION ->
                AppliedVideoStabilizationMode.PREVIEW
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON -> AppliedVideoStabilizationMode.ELECTRONIC
            else -> AppliedVideoStabilizationMode.OFF
        }
        val opticalOn = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE) ==
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        val current = cameraState.value.stabilization
        val observation = CameraStabilizationObservation(videoMode, opticalOn)
        val next = CameraStabilizationReducer.observe(
            current = current,
            observation = observation,
            nowMillis = SystemClock.elapsedRealtime(),
        )
        if (next != current) {
            cameraState.value = cameraState.value.withStabilizationState(next)
            if (next.applyStatus == CameraStabilizationApplyStatus.APPLIED ||
                next.applyStatus == CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM
            ) {
                loggerStabilizationApplied(next, observation)
            }
        }
    }

    private fun loggerStabilizationRequest(mode: CameraStabilizationMode) {
        logger.event(
            "camera_stabilization_requested",
            stabilizationFields(
                requestedMode = mode,
                appliedMode = cameraState.value.stabilization.appliedMode,
                timeToConfirmationMillis = null,
                observation = null,
            ),
        )
    }

    private fun loggerStabilizationApplied(
        state: CameraStabilizationState,
        observation: CameraStabilizationObservation,
    ) {
        logger.event(
            "camera_stabilization_applied",
            stabilizationFields(
                requestedMode = state.requestedMode,
                appliedMode = state.appliedMode,
                timeToConfirmationMillis = state.timeToConfirmationMillis,
                observation = observation,
            ) + mapOf("applyStatus" to state.applyStatus.name),
        )
    }

    private fun stabilizationFields(
        requestedMode: CameraStabilizationMode,
        appliedMode: CameraStabilizationMode,
        timeToConfirmationMillis: Long?,
        observation: CameraStabilizationObservation?,
    ): Map<String, Any?> = mapOf(
        "runId" to diagnosticsRunId,
        "sessionId" to diagnosticsSessionId,
        "cameraId" to selectedCameraId,
        "lens" to cameraState.value.selectedPhysicalLens?.label,
        "resolution" to if (requestedWidth != null && requestedHeight != null) {
            "${requestedWidth}x${requestedHeight}"
        } else {
            null
        },
        "fps" to requestedFps,
        "zoom" to zoomRatio,
        "requestedMode" to requestedMode.name,
        "appliedMode" to appliedMode.name,
        "appliedVideoMode" to observation?.videoMode?.name,
        "appliedOpticalMode" to observation?.let { if (it.opticalOn) OPTICAL_MODE_ON else OPTICAL_MODE_OFF },
        "timeToConfirmationMillis" to timeToConfirmationMillis,
    ).filterValues { it != null }

    private fun antiFlickerCaptureRequestMode(): Int? {
        val supportedModes = availableAntiFlickerModes()
        return effectiveAntiFlickerMode(supportedModes)?.toCaptureRequestMode()
    }

    private fun availableAntiFlickerModes(): List<AntiFlickerMode> {
        val supportedCameraModes = cameraCharacteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
            ?.toSet()
            ?: return emptyList()
        return AntiFlickerMode.entries.filter { mode ->
            mode.toCaptureRequestMode() in supportedCameraModes
        }
    }

    private fun effectiveAntiFlickerMode(supportedModes: List<AntiFlickerMode>): AntiFlickerMode? =
        requestedAntiFlickerMode.takeIf { it in supportedModes }
            ?: AntiFlickerMode.AUTO.takeIf { it in supportedModes }
            ?: supportedModes.firstOrNull()

    private fun AntiFlickerMode.toCaptureRequestMode(): Int = when (this) {
        AntiFlickerMode.AUTO -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        AntiFlickerMode.HZ_50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        AntiFlickerMode.HZ_60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
    }

    private fun updateCameraState() {
        val characteristics = cameraCharacteristics ?: return
        val availableStabilizationModes = availableStabilizationModes()
        val antiFlickerModes = availableAntiFlickerModes()
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING).toLensFacing()
        val availableLensFacings = cameraManager.cameraIdList
            .map(::cameraFacing)
            .filter { it == CameraLensFacing.BACK || it == CameraLensFacing.FRONT }
            .distinct()
        val maximumZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: CameraZoom.DEFAULT_ZOOM_RATIO
        zoomRatio = zoomRatio.coerceIn(CameraZoom.DEFAULT_ZOOM_RATIO, maximumZoom)
        val lensOptions = if (lensFacing == CameraLensFacing.BACK) runCatching {
            val physicalIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                characteristics.physicalCameraIds
            } else {
                emptySet()
            }
            val rearCameraIds = if (physicalIds.isNotEmpty()) {
                physicalIds.filter { cameraFacing(it) == CameraLensFacing.BACK }
            } else {
                cameraManager.cameraIdList.filter { cameraFacing(it) == CameraLensFacing.BACK }
            }
            rearCameraIds
                .distinct()
                .sortedBy(::cameraFocalLength)
                .map { cameraId ->
                    PhysicalLensOption(label = getPhysicalLensLabel(cameraId), cameraId = cameraId)
                }
        }.getOrDefault(emptyList()) else emptyList()
        val selectedCameraFocalLength = selectedCameraId?.let(::cameraFocalLength)
        val selectedLens = lensOptions.firstOrNull { it.cameraId == selectedCameraId }
            ?: selectedCameraFocalLength?.let { focalLength ->
                lensOptions.minByOrNull { option ->
                    abs(cameraFocalLength(option.cameraId ?: return@minByOrNull Float.MAX_VALUE) - focalLength)
                }
            }
            ?: lensOptions.firstOrNull()
        val currentStabilization = cameraState.value.stabilization
        val stabilizationState = if (
            currentStabilization.supportedModes != (listOf(CameraStabilizationMode.OFF) + availableStabilizationModes).distinct() ||
            (requestedStabilizationPreference != CameraStabilizationMode.OFF &&
                requestedStabilizationPreference !in availableStabilizationModes)
        ) {
            CameraStabilizationReducer.withSupportedModes(
                current = currentStabilization,
                supportedModes = availableStabilizationModes,
                persistedPreference = requestedStabilizationPreference,
            )
        } else {
            currentStabilization
        }
        cameraState.value = cameraState.value
            .withCameraBounds(CameraZoom.DEFAULT_ZOOM_RATIO, maximumZoom, zoomRatio)
            .withCameraSelection(
                facing = lensFacing,
                availableFacings = availableLensFacings,
                lensOptions = lensOptions,
                selectedLens = selectedLens,
            )
            .withStabilizationState(stabilizationState)
            .withAntiFlickerSupport(antiFlickerModes)
    }

    private fun getPhysicalLensLabel(physicalId: String): String {
        val physicalChars = runCatching { cameraManager.getCameraCharacteristics(physicalId) }.getOrNull()
            ?: return "Lens $physicalId"
        val focalLengths = physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull() ?: 0.0f
        val mainFocalLengths = firstCameraId(CameraLensFacing.BACK)
            ?.let(cameraManager::getCameraCharacteristics)
            ?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val mainFocalLength = mainFocalLengths?.firstOrNull() ?: focalLength
        if (mainFocalLength > 0.0f && focalLength > 0.0f) {
            val ratio = focalLength / mainFocalLength
            return when {
                ratio < 0.85f -> String.format(java.util.Locale.US, "%.1fx", ratio)
                ratio in 0.85f..1.2f -> "1x"
                else -> String.format(java.util.Locale.US, "%.0fx", ratio)
            }
        }
        return "Lens $physicalId"
    }

    private fun cameraFocalLength(cameraId: String): Float =
        cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()
            ?: Float.MAX_VALUE

    private fun closeCamera() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
    }

    private fun Int?.toLensFacing(): CameraLensFacing = when (this) {
        CameraCharacteristics.LENS_FACING_BACK -> CameraLensFacing.BACK
        CameraCharacteristics.LENS_FACING_FRONT -> CameraLensFacing.FRONT
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraLensFacing.EXTERNAL
        else -> CameraLensFacing.UNKNOWN
    }

    private companion object {
        const val CAMERA_THREAD_NAME = "cambridge-camera"
        const val CAMERA_THREAD_JOIN_TIMEOUT_MILLIS = 2_000L
        const val CAPTURE_SUMMARY_INTERVAL_MILLIS = 1_000L
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val CAPTURE_SUMMARY_INTERVAL_NS = CAPTURE_SUMMARY_INTERVAL_MILLIS * NANOSECONDS_PER_MILLISECOND
        const val MINIMUM_CROP_DIMENSION = 2
        const val CROP_CENTER_DIVISOR = 2
        const val DEFAULT_SENSOR_ORIENTATION_DEGREES = 90
        const val OPTICAL_MODE_ON = "ON"
        const val OPTICAL_MODE_OFF = "OFF"
    }
}
