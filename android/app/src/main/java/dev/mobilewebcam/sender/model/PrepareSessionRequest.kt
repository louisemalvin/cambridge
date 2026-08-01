package dev.mobilewebcam.sender.model

data class PrepareSessionRequest(
    val preferredCodecs: List<VideoCodec>,
    val profile: VideoProfile,
    val bitrateByCodec: Map<VideoCodec, Int>,
)
