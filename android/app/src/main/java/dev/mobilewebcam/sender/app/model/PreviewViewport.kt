package dev.mobilewebcam.sender.app.model

data class PreviewViewport(
    val width: Float,
    val height: Float,
)

object PreviewViewportCalculator {
    fun fit(
        containerWidth: Float,
        containerHeight: Float,
        aspectRatio: Float,
    ): PreviewViewport {
        require(containerWidth >= NON_NEGATIVE_BOUND) { "Container width must not be negative" }
        require(containerHeight >= NON_NEGATIVE_BOUND) { "Container height must not be negative" }
        require(aspectRatio > POSITIVE_FLOAT_BOUND && aspectRatio.isFinite()) {
            "Aspect ratio must be positive and finite"
        }
        if (containerWidth == NON_NEGATIVE_BOUND || containerHeight == NON_NEGATIVE_BOUND) {
            return PreviewViewport(containerWidth, containerHeight)
        }

        val containerAspectRatio = containerWidth / containerHeight
        return if (containerAspectRatio > aspectRatio) {
            PreviewViewport(containerHeight * aspectRatio, containerHeight)
        } else {
            PreviewViewport(containerWidth, containerWidth / aspectRatio)
        }
    }
}

private const val NON_NEGATIVE_BOUND = 0.0f
private const val POSITIVE_FLOAT_BOUND = 0.0f
