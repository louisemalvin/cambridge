package dev.cambridge.sender.media.camera

@ConsistentCopyVisibility
data class PhysicalLensOption internal constructor(
    val label: String,
    internal val cameraId: String?,
)

enum class CameraStabilizationMode {
    OFF,
    OPTICAL,
    ELECTRONIC,
    PREVIEW,
}

enum class CameraStabilizationApplyStatus {
    IDLE,
    APPLYING,
    APPLIED,
    UNAVAILABLE_FOR_STREAM,
}

data class CameraStabilizationState(
    val supportedModes: List<CameraStabilizationMode> = listOf(CameraStabilizationMode.OFF),
    val requestedMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
    val selectedMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
    val applyStatus: CameraStabilizationApplyStatus = CameraStabilizationApplyStatus.IDLE,
    val appliedMode: CameraStabilizationMode = CameraStabilizationMode.OFF,
    val requestedAtMillis: Long? = null,
    val confirmationDeadlineMillis: Long? = null,
    val timeToConfirmationMillis: Long? = null,
) {
    init {
        require(CameraStabilizationMode.OFF in supportedModes) {
            "Off must always be an available stabilization choice"
        }
        require(supportedModes.distinct().size == supportedModes.size) {
            "Stabilization modes must be unique"
        }
        require(selectedMode in supportedModes || applyStatus == CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM) {
            "Selected stabilization mode must be advertised or unavailable"
        }
    }

}

/** Coarse camera state exposed to the UI without camera framework objects. */
data class CameraInteractionState(
    val zoomRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val minZoomRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val maxZoomRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val isCameraActive: Boolean = false,
    val stabilization: CameraStabilizationState = CameraStabilizationState(),
    val supportedAntiFlickerModes: List<AntiFlickerMode> = emptyList(),
    val antiFlickerMode: AntiFlickerMode = AntiFlickerMode.AUTO,
    val physicalLensOptions: List<PhysicalLensOption> = emptyList(),
    val selectedPhysicalLens: PhysicalLensOption? = null,
) {
    init {
        require(minZoomRatio > POSITIVE_FLOAT_BOUND) { "Minimum zoom ratio must be positive" }
        require(maxZoomRatio >= minZoomRatio) { "Maximum zoom ratio must not be below minimum" }
        require(zoomRatio.isFinite()) { "Zoom ratio must be finite" }
        require(selectedPhysicalLens == null || selectedPhysicalLens in physicalLensOptions) {
            "Selected physical lens must be one of the available options"
        }
    }

    val isZoomSupported: Boolean
        get() = maxZoomRatio > minZoomRatio

    fun withZoomRatio(requestedRatio: Float): CameraInteractionState {
        require(requestedRatio.isFinite()) { "Zoom ratio must be finite" }
        return copy(zoomRatio = requestedRatio.coerceIn(minZoomRatio, maxZoomRatio))
    }

    fun withCameraBounds(
        minimum: Float,
        maximum: Float,
        current: Float = zoomRatio,
    ): CameraInteractionState {
        require(minimum.isFinite() && minimum > POSITIVE_FLOAT_BOUND) {
            "Minimum zoom ratio must be positive and finite"
        }
        require(maximum.isFinite() && maximum >= minimum) {
            "Maximum zoom ratio must be finite and not below minimum"
        }
        require(current.isFinite()) { "Zoom ratio must be finite" }
        return copy(
            minZoomRatio = minimum,
            maxZoomRatio = maximum,
            zoomRatio = current.coerceIn(minimum, maximum),
            isCameraActive = true,
        )
    }

    fun resetZoom(): CameraInteractionState = withZoomRatio(CameraZoom.DEFAULT_ZOOM_RATIO)

    fun withStabilizationState(value: CameraStabilizationState): CameraInteractionState =
        copy(stabilization = value)

    fun withAntiFlickerSupport(modes: List<AntiFlickerMode>): CameraInteractionState {
        val supportedModes = modes.distinct()
        val effectiveMode = antiFlickerMode.takeIf { it in supportedModes }
            ?: AntiFlickerMode.AUTO.takeIf { it in supportedModes }
            ?: supportedModes.firstOrNull()
        return copy(
            supportedAntiFlickerModes = supportedModes,
            antiFlickerMode = effectiveMode ?: AntiFlickerMode.AUTO,
        )
    }

    fun withAntiFlickerMode(mode: AntiFlickerMode): CameraInteractionState {
        require(mode in supportedAntiFlickerModes) {
            "Anti-flicker mode is not supported by the selected camera"
        }
        return copy(antiFlickerMode = mode)
    }

    fun withPhysicalLensOptions(
        options: List<PhysicalLensOption>,
    ): CameraInteractionState {
        val selectedLens = selectedPhysicalLens?.takeIf { it in options }
            ?: options.firstOrNull()
        return copy(
            physicalLensOptions = options,
            selectedPhysicalLens = selectedLens,
        )
    }

    fun withSelectedPhysicalLens(lens: PhysicalLensOption): CameraInteractionState {
        require(lens in physicalLensOptions) { "Selected physical lens is unavailable" }
        return copy(selectedPhysicalLens = lens)
    }

    companion object {
        fun inactive(): CameraInteractionState = CameraInteractionState()
    }
}

private const val POSITIVE_FLOAT_BOUND = 0.0f
