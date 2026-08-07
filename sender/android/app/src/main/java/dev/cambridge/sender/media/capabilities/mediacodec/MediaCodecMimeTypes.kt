package dev.cambridge.sender.media.capabilities.mediacodec

import android.media.MediaFormat
import dev.cambridge.sender.model.VideoCodec

internal val VideoCodec.mediaCodecMimeType: String
    get() = when (this) {
        VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
    }
