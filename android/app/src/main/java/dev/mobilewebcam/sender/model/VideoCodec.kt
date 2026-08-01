package dev.mobilewebcam.sender.model

import android.media.MediaFormat

enum class VideoCodec(
    val protocolId: String,
    val androidMimeType: String,
) {
    H264(
        protocolId = "h264",
        androidMimeType = MediaFormat.MIMETYPE_VIDEO_AVC,
    ),
    H265(
        protocolId = "h265",
        androidMimeType = MediaFormat.MIMETYPE_VIDEO_HEVC,
    ),
}
