package dev.mobilewebcam.sender.model

data class VideoProfile(
    val id: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val h264BitrateBps: Int,
    val h265BitrateBps: Int,
    val keyframeIntervalSeconds: Int,
) {
    init {
        require(width > 0 && height > 0 && fps > 0) { "Video dimensions and FPS must be positive" }
        require(h264BitrateBps > 0 && h265BitrateBps > 0) { "Bitrates must be positive" }
        require(keyframeIntervalSeconds > 0) { "Keyframe interval must be positive" }
    }

    fun bitrateFor(codec: VideoCodec): Int = when (codec) {
        VideoCodec.H264 -> h264BitrateBps
        VideoCodec.H265 -> h265BitrateBps
    }
}
