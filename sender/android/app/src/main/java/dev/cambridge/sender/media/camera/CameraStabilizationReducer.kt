package dev.cambridge.sender.media.camera

/** Camera2-independent representation of the two stabilization result keys. */
enum class AppliedVideoStabilizationMode {
    OFF,
    ELECTRONIC,
    PREVIEW,
}

enum class RequestedVideoStabilizationMode {
    OFF,
    ELECTRONIC,
    PREVIEW,
}

data class CameraStabilizationRequest(
    val videoMode: RequestedVideoStabilizationMode,
    val opticalOn: Boolean,
)

data class CameraStabilizationObservation(
    val videoMode: AppliedVideoStabilizationMode,
    val opticalOn: Boolean,
)

object CameraStabilizationReducer {
    /** Android may need several frames before a requested mode appears in results. */
    const val CONFIRMATION_DEADLINE_MILLIS = 500L

    fun requestFor(mode: CameraStabilizationMode): CameraStabilizationRequest = when (mode) {
        CameraStabilizationMode.OFF -> CameraStabilizationRequest(
            videoMode = RequestedVideoStabilizationMode.OFF,
            opticalOn = false,
        )
        CameraStabilizationMode.OPTICAL -> CameraStabilizationRequest(
            videoMode = RequestedVideoStabilizationMode.OFF,
            opticalOn = true,
        )
        CameraStabilizationMode.ELECTRONIC -> CameraStabilizationRequest(
            videoMode = RequestedVideoStabilizationMode.ELECTRONIC,
            opticalOn = false,
        )
        CameraStabilizationMode.PREVIEW -> CameraStabilizationRequest(
            videoMode = RequestedVideoStabilizationMode.PREVIEW,
            opticalOn = false,
        )
    }

    fun withSupportedModes(
        current: CameraStabilizationState,
        supportedModes: List<CameraStabilizationMode>,
        persistedPreference: CameraStabilizationMode,
    ): CameraStabilizationState {
        val modes = (listOf(CameraStabilizationMode.OFF) + supportedModes).distinct()
        val effectiveMode = persistedPreference.takeIf { it in modes } ?: CameraStabilizationMode.OFF
        val unavailable = persistedPreference != CameraStabilizationMode.OFF && effectiveMode == CameraStabilizationMode.OFF
        return current.copy(
            supportedModes = modes,
            requestedMode = persistedPreference,
            selectedMode = effectiveMode,
            applyStatus = if (unavailable) {
                CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM
            } else {
                CameraStabilizationApplyStatus.IDLE
            },
            appliedMode = CameraStabilizationMode.OFF,
            requestedAtMillis = null,
            confirmationDeadlineMillis = null,
            timeToConfirmationMillis = null,
        )
    }

    fun request(
        current: CameraStabilizationState,
        requestedMode: CameraStabilizationMode,
        nowMillis: Long,
    ): CameraStabilizationState {
        if (requestedMode !in current.supportedModes) {
            return current.copy(
                selectedMode = CameraStabilizationMode.OFF,
                requestedMode = requestedMode,
                applyStatus = CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM,
                appliedMode = CameraStabilizationMode.OFF,
                requestedAtMillis = null,
                confirmationDeadlineMillis = null,
                timeToConfirmationMillis = null,
            )
        }
        return current.copy(
            selectedMode = requestedMode,
            requestedMode = requestedMode,
            applyStatus = CameraStabilizationApplyStatus.APPLYING,
            appliedMode = CameraStabilizationMode.OFF,
            requestedAtMillis = nowMillis,
            confirmationDeadlineMillis = nowMillis + CONFIRMATION_DEADLINE_MILLIS,
            timeToConfirmationMillis = null,
        )
    }

    fun observe(
        current: CameraStabilizationState,
        observation: CameraStabilizationObservation,
        nowMillis: Long,
    ): CameraStabilizationState {
        if (current.applyStatus == CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM &&
            current.requestedMode != CameraStabilizationMode.OFF
        ) {
            return current
        }
        val appliedMode = observation.toAppliedMode()
        val matches = matches(current.selectedMode, observation)
        if (matches) {
            return current.copy(
                appliedMode = appliedMode,
                applyStatus = CameraStabilizationApplyStatus.APPLIED,
                confirmationDeadlineMillis = null,
                timeToConfirmationMillis = current.requestedAtMillis?.let { nowMillis - it },
            )
        }
        if (current.applyStatus == CameraStabilizationApplyStatus.APPLYING &&
            current.confirmationDeadlineMillis != null &&
            nowMillis >= current.confirmationDeadlineMillis
        ) {
            return current.copy(
                appliedMode = CameraStabilizationMode.OFF,
                applyStatus = CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM,
                confirmationDeadlineMillis = null,
                timeToConfirmationMillis = null,
            )
        }
        return current
    }

    private fun matches(
        requestedMode: CameraStabilizationMode,
        observation: CameraStabilizationObservation,
    ): Boolean = when (requestedMode) {
        CameraStabilizationMode.OFF ->
            observation.videoMode == AppliedVideoStabilizationMode.OFF && !observation.opticalOn
        CameraStabilizationMode.OPTICAL ->
            observation.videoMode == AppliedVideoStabilizationMode.OFF && observation.opticalOn
        CameraStabilizationMode.ELECTRONIC ->
            observation.videoMode == AppliedVideoStabilizationMode.ELECTRONIC && !observation.opticalOn
        CameraStabilizationMode.PREVIEW ->
            observation.videoMode == AppliedVideoStabilizationMode.PREVIEW
    }

    private fun CameraStabilizationObservation.toAppliedMode(): CameraStabilizationMode = when {
        videoMode == AppliedVideoStabilizationMode.PREVIEW -> CameraStabilizationMode.PREVIEW
        videoMode == AppliedVideoStabilizationMode.ELECTRONIC -> CameraStabilizationMode.ELECTRONIC
        opticalOn -> CameraStabilizationMode.OPTICAL
        else -> CameraStabilizationMode.OFF
    }
}
