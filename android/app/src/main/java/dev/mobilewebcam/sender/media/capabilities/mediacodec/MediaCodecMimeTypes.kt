package dev.mobilewebcam.sender.media.capabilities.mediacodec

import android.media.MediaFormat
import dev.mobilewebcam.sender.model.VideoCodec

internal val VideoCodec.mediaCodecMimeType: String
    get() = when (this) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
        VideoCodec.H265 -> MediaFormat.MIMETYPE_VIDEO_HEVC
    }
