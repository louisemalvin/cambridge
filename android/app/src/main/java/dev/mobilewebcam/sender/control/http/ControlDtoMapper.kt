package dev.mobilewebcam.sender.control.http

import dev.mobilewebcam.sender.model.DecoderAcceleration
import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverCodecCapability
import dev.mobilewebcam.sender.model.VideoCodec

internal fun ControlCodec.toDomain(): VideoCodec = when (this) {
    ControlCodec.H264 -> VideoCodec.H264
    ControlCodec.H265 -> VideoCodec.H265
}

internal fun VideoCodec.toDto(): ControlCodec = when (this) {
    VideoCodec.H264 -> ControlCodec.H264
    VideoCodec.H265 -> ControlCodec.H265
}

internal fun ControlDecoderAcceleration.toDomain(): DecoderAcceleration = when (this) {
    ControlDecoderAcceleration.HARDWARE -> DecoderAcceleration.HARDWARE
    ControlDecoderAcceleration.SOFTWARE -> DecoderAcceleration.SOFTWARE
    ControlDecoderAcceleration.UNKNOWN -> DecoderAcceleration.UNKNOWN
}

internal fun ControlPixelFormat.toDomain(): OutputPixelFormat = when (this) {
    ControlPixelFormat.YUY2 -> OutputPixelFormat.YUY2
    ControlPixelFormat.NV12 -> OutputPixelFormat.NV12
    ControlPixelFormat.I420 -> OutputPixelFormat.I420
}

internal fun CapabilitiesResponseDto.toDomain(): ReceiverCapabilities =
    ReceiverCapabilities(
        protocolVersion = protocolVersion,
        mediaPort = media.defaultPort,
        codecs = videoCodecs.map {
            ReceiverCodecCapability(
                codec = it.codec.toDomain(),
                supported = it.supported,
                decoderAcceleration = it.decoderAcceleration.toDomain(),
            )
        },
        outputDevice = output.device,
        pixelFormats = output.pixelFormats.map(ControlPixelFormat::toDomain),
        activeSession = session.active,
    )
