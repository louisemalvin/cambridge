package dev.mobilewebcam.sender.model

enum class StreamOrientation(
    val isPortrait: Boolean,
    val isReversed: Boolean,
    val displayRotationDegrees: Int,
) {
    LANDSCAPE(
        isPortrait = false,
        isReversed = false,
        displayRotationDegrees = DISPLAY_ROTATION_LANDSCAPE_DEGREES,
    ),
    LANDSCAPE_REVERSED(
        isPortrait = false,
        isReversed = true,
        displayRotationDegrees = DISPLAY_ROTATION_REVERSE_LANDSCAPE_DEGREES,
    ),
    PORTRAIT(
        isPortrait = true,
        isReversed = false,
        displayRotationDegrees = DISPLAY_ROTATION_PORTRAIT_DEGREES,
    ),
    PORTRAIT_REVERSED(
        isPortrait = true,
        isReversed = true,
        displayRotationDegrees = DISPLAY_ROTATION_REVERSE_PORTRAIT_DEGREES,
    );

    companion object {
        fun fromDisplayRotation(rotationDegrees: Int): StreamOrientation {
            val normalizedRotation = rotationDegrees.mod(FULL_TURN_DEGREES)
            return entries.firstOrNull { orientation ->
                orientation.displayRotationDegrees == normalizedRotation
            } ?: error("Unsupported display rotation: $rotationDegrees")
        }
    }
}

private const val DISPLAY_ROTATION_PORTRAIT_DEGREES = 0
private const val DISPLAY_ROTATION_LANDSCAPE_DEGREES = 90
private const val DISPLAY_ROTATION_REVERSE_PORTRAIT_DEGREES = 180
private const val DISPLAY_ROTATION_REVERSE_LANDSCAPE_DEGREES = 270
private const val FULL_TURN_DEGREES = 360
