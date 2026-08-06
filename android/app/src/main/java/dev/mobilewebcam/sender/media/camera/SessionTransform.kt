package dev.mobilewebcam.sender.media.camera

import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract

enum class CameraLensFacing {
    BACK,
    FRONT,
    EXTERNAL,
    UNKNOWN,
}

data class SessionTransform(
    val displayOrientation: DisplayOrientation,
    val sensorOrientationDegrees: Int,
    val lensFacing: CameraLensFacing,
    val rotationDegrees: Int,
    val codedWidth: Int,
    val codedHeight: Int,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val isPortrait: Boolean = displayOrientation.isPortrait

    val modeLabel: String
        get() = if (isPortrait) "Portrait" else "Landscape"

    init {
        require(sensorOrientationDegrees in ROTATION_DEGREES) {
            "Camera sensor orientation must be a right angle"
        }
        require(rotationDegrees in ROTATION_DEGREES) {
            "Session rotation must be a right angle"
        }
        require(codedWidth > ZERO_DIMENSION && codedHeight > ZERO_DIMENSION)
        require(displayWidth > ZERO_DIMENSION && displayHeight > ZERO_DIMENSION)
    }

    companion object {
        fun calculate(
            displayOrientation: DisplayOrientation,
            sensorOrientationDegrees: Int,
            lensFacing: CameraLensFacing,
            codedWidth: Int,
            codedHeight: Int,
        ): SessionTransform {
            val normalizedSensor = normalizeRotation(sensorOrientationDegrees)
            val deviceRotation = displayOrientation.rotationDegrees
            val sensorRotation = when (lensFacing) {
                CameraLensFacing.FRONT -> normalizedSensor + deviceRotation
                CameraLensFacing.BACK,
                CameraLensFacing.EXTERNAL,
                CameraLensFacing.UNKNOWN,
                -> normalizedSensor - deviceRotation + FULL_TURN_DEGREES
            }
            val normalizedRotation = normalizeRotation(sensorRotation)
            val rotationMatchesShape = normalizedRotation.isQuarterTurn() == displayOrientation.isPortrait
            val sessionRotation = if (rotationMatchesShape) {
                normalizedRotation
            } else {
                normalizeRotation(normalizedRotation + QUARTER_TURN_DEGREES)
            }
            val displayWidth = if (displayOrientation.isPortrait) codedHeight else codedWidth
            val displayHeight = if (displayOrientation.isPortrait) codedWidth else codedHeight
            return SessionTransform(
                displayOrientation = displayOrientation,
                sensorOrientationDegrees = normalizedSensor,
                lensFacing = lensFacing,
                rotationDegrees = sessionRotation,
                codedWidth = codedWidth,
                codedHeight = codedHeight,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )
        }

        fun forProfile(
            displayOrientation: DisplayOrientation = DisplayOrientation.LANDSCAPE,
            codedWidth: Int = DirectStreamContract.DEFAULT_CODED_WIDTH,
            codedHeight: Int = DirectStreamContract.DEFAULT_CODED_HEIGHT,
        ): SessionTransform = calculate(
            displayOrientation = displayOrientation,
            sensorOrientationDegrees = DEFAULT_SENSOR_ORIENTATION_DEGREES,
            lensFacing = CameraLensFacing.BACK,
            codedWidth = codedWidth,
            codedHeight = codedHeight,
        )

        private fun normalizeRotation(rotationDegrees: Int): Int =
            rotationDegrees.mod(FULL_TURN_DEGREES)

        private fun Int.isQuarterTurn(): Boolean = this == QUARTER_TURN_DEGREES ||
            this == THREE_QUARTER_TURN_DEGREES

        private val ROTATION_DEGREES = setOf(0, 90, 180, 270)
        private const val ZERO_DIMENSION = 0
        private const val FULL_TURN_DEGREES = 360
        private const val QUARTER_TURN_DEGREES = 90
        private const val THREE_QUARTER_TURN_DEGREES = 270
        private const val DEFAULT_SENSOR_ORIENTATION_DEGREES = 90
    }
}
