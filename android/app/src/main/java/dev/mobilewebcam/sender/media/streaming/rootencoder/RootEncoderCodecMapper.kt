package dev.mobilewebcam.sender.media.streaming.rootencoder

import com.pedro.common.VideoCodec as RootVideoCodec
import dev.mobilewebcam.sender.model.VideoCodec

internal fun VideoCodec.toRootEncoder(): RootVideoCodec = when (this) {
    VideoCodec.H264 -> RootVideoCodec.H264
    VideoCodec.H265 -> RootVideoCodec.H265
}
