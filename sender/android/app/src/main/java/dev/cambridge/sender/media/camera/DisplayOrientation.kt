package dev.cambridge.sender.media.camera

import android.view.Surface
import dev.cambridge.sender.model.StreamOrientation

private const val ROTATION_0_DEGREES = 0
private const val ROTATION_90_DEGREES = 90
private const val ROTATION_180_DEGREES = 180
private const val ROTATION_270_DEGREES = 270
private const val FULL_TURN_DEGREES = 360

/** Display rotations used to choose the preview shape and camera capture rotation. */
enum class DisplayOrientation(
    val rotationDegrees: Int,
    val isPortrait: Boolean,
    val cameraRotationDegrees: Int,
) {
    PORTRAIT(
        rotationDegrees = ROTATION_0_DEGREES,
        isPortrait = true,
        cameraRotationDegrees = ROTATION_0_DEGREES,
    ),
    LANDSCAPE(
        rotationDegrees = ROTATION_90_DEGREES,
        isPortrait = false,
        cameraRotationDegrees = ROTATION_270_DEGREES,
    ),
    REVERSE_PORTRAIT(
        rotationDegrees = ROTATION_180_DEGREES,
        isPortrait = true,
        cameraRotationDegrees = ROTATION_180_DEGREES,
    ),
    REVERSE_LANDSCAPE(
        rotationDegrees = ROTATION_270_DEGREES,
        isPortrait = false,
        cameraRotationDegrees = ROTATION_90_DEGREES,
    );

    companion object {
        fun fromRotationDegrees(rotationDegrees: Int): DisplayOrientation {
            return when (rotationDegrees.mod(FULL_TURN_DEGREES)) {
                ROTATION_0_DEGREES -> PORTRAIT
                ROTATION_90_DEGREES -> LANDSCAPE
                ROTATION_180_DEGREES -> REVERSE_PORTRAIT
                ROTATION_270_DEGREES -> REVERSE_LANDSCAPE
                else -> error("Unsupported display rotation: $rotationDegrees")
            }
        }

        fun fromSurfaceRotation(surfaceRotation: Int): DisplayOrientation = when (surfaceRotation) {
            Surface.ROTATION_0 -> PORTRAIT
            Surface.ROTATION_90 -> LANDSCAPE
            Surface.ROTATION_180 -> REVERSE_PORTRAIT
            Surface.ROTATION_270 -> REVERSE_LANDSCAPE
            else -> error("Unsupported surface rotation: $surfaceRotation")
        }

        fun fromPortraitFlag(isPortrait: Boolean): DisplayOrientation =
            if (isPortrait) PORTRAIT else LANDSCAPE
    }
}

fun StreamOrientation.toDisplayOrientation(): DisplayOrientation =
    DisplayOrientation.fromRotationDegrees(displayRotationDegrees)
