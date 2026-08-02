package dev.mobilewebcam.sender.model

enum class VideoCodec(
    val protocolId: String,
) {
    H264(
        protocolId = "h264",
    ),
    H265(
        protocolId = "h265",
    ),
}
