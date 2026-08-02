package dev.mobilewebcam.sender.camera

import android.view.Surface

data class CameraPreviewSurface(
    val surface: Surface,
    val width: Int,
    val height: Int,
    val orientation: DisplayOrientation,
) {
    init {
        require(width > MINIMUM_PREVIEW_DIMENSION) { "Preview width must be positive" }
        require(height > MINIMUM_PREVIEW_DIMENSION) { "Preview height must be positive" }
    }
}

internal const val MINIMUM_PREVIEW_DIMENSION = 0
