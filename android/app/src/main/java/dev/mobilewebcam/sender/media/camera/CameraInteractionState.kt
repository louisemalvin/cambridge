package dev.mobilewebcam.sender.media.camera

import dev.mobilewebcam.sender.config.CameraZoom

@ConsistentCopyVisibility
data class PhysicalLensOption internal constructor(
    val label: String,
    internal val cameraId: String?,
)

/** Coarse camera state exposed to the UI without camera framework objects. */
data class CameraInteractionState(
    val zoomRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val minZoomRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val maxZoomRatio: Float = CameraZoom.DEFAULT_ZOOM_RATIO,
    val isCameraActive: Boolean = false,
    val isStabilizationSupported: Boolean = false,
    val isStabilizationEnabled: Boolean = false,
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

    fun withStabilizationSupport(supported: Boolean): CameraInteractionState = copy(
        isStabilizationSupported = supported,
        isStabilizationEnabled = isStabilizationEnabled && supported,
    )

    fun withStabilizationEnabled(enabled: Boolean): CameraInteractionState {
        require(!enabled || isStabilizationSupported) {
            "Stabilization cannot be enabled when unsupported"
        }
        return copy(isStabilizationEnabled = enabled)
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

internal data class CameraStabilizationSupport(
    val opticalSupported: Boolean,
    val electronicSupported: Boolean,
) {
    val isSupported: Boolean
        get() = opticalSupported || electronicSupported
}

internal enum class CameraStabilizationMode {
    OPTICAL,
    ELECTRONIC,
}

internal fun preferredStabilizationMode(
    support: CameraStabilizationSupport,
): CameraStabilizationMode? = when {
    support.opticalSupported -> CameraStabilizationMode.OPTICAL
    support.electronicSupported -> CameraStabilizationMode.ELECTRONIC
    else -> null
}

internal fun physicalLensOptionsFor(cameraIds: List<String>): List<PhysicalLensOption> {
    if (cameraIds.isEmpty()) return emptyList()

    val options = buildList {
        add(PhysicalLensOption(AUTOMATIC_LENS_LABEL, null))
        cameraIds.distinct().forEach { cameraId ->
            add(PhysicalLensOption("$PHYSICAL_LENS_LABEL_PREFIX$cameraId", cameraId))
        }
    }
    return options
}

private const val AUTOMATIC_LENS_LABEL = "Auto"
private const val PHYSICAL_LENS_LABEL_PREFIX = "Lens "
