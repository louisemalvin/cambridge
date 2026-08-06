package dev.mobilewebcam.sender.model

data class VideoProfile(
    val id: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val h264BitrateBps: Int,
    val keyframeIntervalSeconds: Int,
) {
    init {
        require(id.isNotBlank()) { "Video profile ID must not be blank" }
        require(width > 0 && height > 0 && fps > 0) { "Video dimensions and FPS must be positive" }
        require(h264BitrateBps > 0) { "Bitrate must be positive" }
        require(keyframeIntervalSeconds > 0) { "Keyframe interval must be positive" }
    }

    fun bitrateFor(codec: VideoCodec): Int = when (codec) {
        VideoCodec.H264 -> h264BitrateBps
    }
}
