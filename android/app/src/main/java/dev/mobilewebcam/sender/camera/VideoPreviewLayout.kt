package dev.mobilewebcam.sender.camera

import dev.mobilewebcam.sender.model.VideoProfile

data class PreviewDimensions(
    val width: Int,
    val height: Int,
)

data class VideoPreviewLayout(
    val dimensions: PreviewDimensions,
) {
    val aspectRatio: Float
        get() = dimensions.width.toFloat() / dimensions.height

    companion object {
        fun forProfile(
            profile: VideoProfile,
            orientation: DisplayOrientation,
        ): VideoPreviewLayout {
            val dimensions = if (orientation.isPortrait) {
                PreviewDimensions(profile.height, profile.width)
            } else {
                PreviewDimensions(profile.width, profile.height)
            }
            return VideoPreviewLayout(dimensions)
        }
    }
}
