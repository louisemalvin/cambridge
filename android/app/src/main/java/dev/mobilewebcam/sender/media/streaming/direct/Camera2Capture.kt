package dev.mobilewebcam.sender.media.streaming.direct

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import androidx.core.content.ContextCompat
import dev.mobilewebcam.sender.logging.AndroidAppLogger
import dev.mobilewebcam.sender.logging.AppLogger
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.media.camera.CameraZoom
import dev.mobilewebcam.sender.media.camera.PhysicalLensOption
import dev.mobilewebcam.sender.media.camera.CameraLensFacing
import dev.mobilewebcam.sender.media.camera.DisplayOrientation
import dev.mobilewebcam.sender.media.camera.SessionTransform
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.model.StreamOrientation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    private var previewSurface: CameraPreviewSurface? = null
    private var selectedCameraId: String? = null
    private var cameraCharacteristics: CameraCharacteristics? = null
    private var zoomRatio = CameraZoom.DEFAULT_ZOOM_RATIO
    private var requestedStabilizationEnabled = true
    private var stabilizationEnabled = false
    private var loggedFirstCapture = false
    private var captureResultCount = 0L
    private var captureSummaryStartNs = 0L
    private var captureSummaryStartCount = 0L
    private var lastSensorTimestampNs = 0L

    val cameraState = kotlinx.coroutines.flow.MutableStateFlow(CameraInteractionState())

    suspend fun prepare() {
        check(ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            "Camera permission is required before preparing the direct sender"
        }
        startThread()
        val cameraId = selectedCameraId ?: selectDefaultCameraId().also { selectedCameraId = it }
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
        updateCameraState()
    }

    suspend fun snapshotSessionTransform(
        codedWidth: Int,
        codedHeight: Int,
        orientation: StreamOrientation,
    ): SessionTransform {
        check(ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED) {
            "Camera permission is required before selecting video quality"
        }
        if (cameraCharacteristics == null) {
            startThread()
            val cameraId = selectDefaultCameraId()
            selectedCameraId = cameraId
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            updateCameraState()
        }
        val characteristics = cameraCharacteristics ?: error("Camera characteristics are unavailable")
        val selectedOrientation = DisplayOrientation.fromPortraitFlag(orientation.isPortrait)
        val displayOrientation = previewSurface?.orientation
            ?.takeIf { it.isPortrait == orientation.isPortrait }
            ?: selectedOrientation
        return SessionTransform.calculate(
            displayOrientation = displayOrientation,
            sensorOrientationDegrees = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)
                ?: DEFAULT_SENSOR_ORIENTATION_DEGREES,
            lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING).toLensFacing(),
            codedWidth = codedWidth,
            codedHeight = codedHeight,
        )
    }

    suspend fun start(surface: Surface, targetFps: Int) {
        require(targetFps in DirectStreamContract.MINIMUM_FPS..DirectStreamContract.MAXIMUM_FPS) {
            "Requested frame rate is outside the direct stream contract"
        }
        requestedFps = targetFps
        encoderSurface = surface
        val cameraId = selectedCameraId ?: selectDefaultCameraId().also { selectedCameraId = it }
        val device = openCamera(cameraId)
        cameraDevice = device
        configureCaptureSession(device)
    }

    suspend fun stop() {
        closeCamera()
        encoderSurface = null
        requestedFps = null
        stabilizationEnabled = false
        loggedFirstCapture = false
        captureResultCount = 0L
        captureSummaryStartNs = 0L
        captureSummaryStartCount = 0L
        lastSensorTimestampNs = 0L
        cameraState.value = CameraInteractionState.inactive()
        stopThread()
    }

    suspend fun setPreviewSurface(surface: CameraPreviewSurface?) {
        previewSurface = surface
        if (cameraDevice != null && encoderSurface != null) {
            captureSession?.close()
            captureSession = null
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

    suspend fun setStabilizationEnabled(enabled: Boolean) {
        requestedStabilizationEnabled = enabled
        stabilizationEnabled = enabled && isStabilizationSupported()
        submitRepeatingRequest()
        cameraState.value = cameraState.value.withStabilizationEnabled(stabilizationEnabled)
    }

    suspend fun selectPhysicalLens(lens: PhysicalLensOption) {
        val requestedId = lens.cameraId ?: return
        if (requestedId == selectedCameraId) return
        val wasRunning = cameraDevice != null
        if (wasRunning) closeCamera()
        selectedCameraId = requestedId
        cameraCharacteristics = cameraManager.getCameraCharacteristics(requestedId)
        updateCameraState()
        if (wasRunning) {
            val surface = encoderSurface ?: return
            start(
                surface,
                targetFps = requestedFps
                    ?: error("Requested frame rate is unavailable while switching lenses"),
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
        val backCamera = ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        return backCamera ?: ids.firstOrNull() ?: error("No Android camera is available")
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(cameraId: String): CameraDevice = suspendCancellableCoroutine { continuation ->
        val manager = cameraManager
        val handler = cameraHandler ?: run {
            continuation.resumeWithException(IllegalStateException("Camera handler is not running"))
            return@suspendCancellableCoroutine
        }
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
        continuation.invokeOnCancellation { cameraDevice?.close() }
    }

    @Suppress("DEPRECATION")
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
        device.createCaptureSession(
            outputs,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    runCatching { submitRepeatingRequest() }
                        .onSuccess { continuation.resume(Unit) }
                        .onFailure(continuation::resumeWithException)
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    continuation.resumeWithException(IllegalStateException("Camera capture session configuration failed"))
                }
            },
            handler,
        )
        continuation.invokeOnCancellation { captureSession?.close() }
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
            autoAntiBandingMode()?.let { mode -> set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, mode) }
            cropRegion()?.let { crop -> set(CaptureRequest.SCALER_CROP_REGION, crop) }
            if (isStabilizationSupported()) {
                set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    if (stabilizationEnabled) {
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    } else {
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                    },
                )
            }
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
                    val sensorTimestampNs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                    val previousSensorTimestampNs = lastSensorTimestampNs
                    captureResultCount += 1L
                    lastSensorTimestampNs = sensorTimestampNs
                    if (!loggedFirstCapture) {
                        loggedFirstCapture = true
                        logger.event(
                            "camera_first_capture_result",
                            mapOf("sensorTimestampNs" to sensorTimestampNs),
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

    private fun isStabilizationSupported(): Boolean {
        val modes = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?: return false
        return modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
    }

    private fun autoAntiBandingMode(): Int? {
        val modes = cameraCharacteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)
            ?: return null
        return CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO.takeIf { mode -> mode in modes }
    }

    private fun updateCameraState() {
        val characteristics = cameraCharacteristics ?: return
        val stabilizationSupported = isStabilizationSupported()
        stabilizationEnabled = requestedStabilizationEnabled && stabilizationSupported
        val maximumZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: CameraZoom.DEFAULT_ZOOM_RATIO
        val lensOptions = runCatching {
            val physicalIds = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                characteristics.physicalCameraIds
            } else {
                emptySet()
            }
            if (physicalIds.isNotEmpty()) {
                physicalIds.map { physicalId ->
                    val label = getPhysicalLensLabel(physicalId)
                    PhysicalLensOption(label = label, cameraId = physicalId)
                }
            } else {
                cameraManager.cameraIdList.map { cameraId ->
                    PhysicalLensOption(label = "Camera $cameraId", cameraId = cameraId)
                }
            }
        }.getOrDefault(emptyList())
        cameraState.value = cameraState.value
            .withCameraBounds(CameraZoom.DEFAULT_ZOOM_RATIO, maximumZoom, zoomRatio)
            .withPhysicalLensOptions(lensOptions)
            .withStabilizationSupport(stabilizationSupported)
            .withStabilizationEnabled(stabilizationEnabled)
    }

    private fun getPhysicalLensLabel(physicalId: String): String {
        val physicalChars = runCatching { cameraManager.getCameraCharacteristics(physicalId) }.getOrNull()
            ?: return "Lens $physicalId"
        val focalLengths = physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull() ?: 0.0f
        val mainFocalLengths = cameraCharacteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
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
        const val CAMERA_THREAD_NAME = "direct-webcam-camera"
        const val CAMERA_THREAD_JOIN_TIMEOUT_MILLIS = 2_000L
        const val CAPTURE_SUMMARY_INTERVAL_MILLIS = 1_000L
        const val NANOSECONDS_PER_MILLISECOND = 1_000_000L
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val CAPTURE_SUMMARY_INTERVAL_NS = CAPTURE_SUMMARY_INTERVAL_MILLIS * NANOSECONDS_PER_MILLISECOND
        const val MINIMUM_CROP_DIMENSION = 2
        const val CROP_CENTER_DIVISOR = 2
        const val DEFAULT_SENSOR_ORIENTATION_DEGREES = 90
    }
}
