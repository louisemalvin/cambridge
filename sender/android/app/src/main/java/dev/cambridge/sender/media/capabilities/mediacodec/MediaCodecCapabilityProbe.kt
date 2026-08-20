package dev.cambridge.sender.media.capabilities.mediacodec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import dev.cambridge.sender.media.capabilities.EncoderCapabilityProbe
import dev.cambridge.sender.model.EncoderAcceleration
import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.EncoderModeCapability
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class MediaCodecCapabilityProbe : EncoderCapabilityProbe {
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, List<EncoderCapability>>()

    override suspend fun getCapabilities(profiles: List<VideoProfile>): List<EncoderCapability> {
        val cacheKey = profiles.joinToString(separator = ",") {
            "${it.id}:${it.width}x${it.height}@${it.fps}"
        }
        cacheMutex.withLock { cache[cacheKey] }?.let { return it }
        val result = withContext(Dispatchers.Default) {
            probe(profiles)
        }
        cacheMutex.withLock {
            cache[cacheKey] = result
        }
        return result
    }

    private fun probe(profiles: List<VideoProfile>): List<EncoderCapability> {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        return codecInfos
            .filter { info ->
                info.isEncoder && info.supportedTypes.any { type ->
                    type.equals(VideoCodec.H264.mediaCodecMimeType, ignoreCase = true)
                }
            }
            .map { info -> probeEncoder(info, profiles) }
    }

    private fun probeEncoder(
        info: MediaCodecInfo,
        profiles: List<VideoProfile>,
    ): EncoderCapability {
        val capabilities = runCatching {
            info.getCapabilitiesForType(VideoCodec.H264.mediaCodecMimeType)
        }.getOrNull()
        val video = capabilities?.videoCapabilities
        val bitrateRange = video?.let { runCatching { it.bitrateRange }.getOrNull() }
        val surfaceInputSupported = capabilities?.colorFormats?.contains(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
        ) == true
        return EncoderCapability(
            codec = VideoCodec.H264,
            implementationName = info.name,
            acceleration = acceleration(info),
            surfaceInputSupported = surfaceInputSupported,
            modes = profiles.map { profile ->
                val sizeAndRateSupported = video?.let {
                    runCatching {
                        it.areSizeAndRateSupported(
                            profile.width,
                            profile.height,
                            profile.fps.toDouble(),
                        )
                    }.getOrDefault(false)
                } == true
                EncoderModeCapability(
                    modeId = profile.id,
                    sizeAndRateSupported = sizeAndRateSupported,
                    minimumBitrateBps = bitrateRange?.lower,
                    maximumBitrateBps = bitrateRange?.upper,
                    reason = when {
                        capabilities == null -> "Encoder capability metadata was unavailable"
                        video == null -> "Video capability metadata was unavailable"
                        !sizeAndRateSupported ->
                            "Resolution or frame rate is outside codec capabilities"
                        else -> null
                    },
                )
            },
        )
    }

    private fun acceleration(info: MediaCodecInfo): EncoderAcceleration {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return EncoderAcceleration.UNKNOWN
        }
        return when {
            info.isHardwareAccelerated -> EncoderAcceleration.HARDWARE
            info.isSoftwareOnly -> EncoderAcceleration.SOFTWARE
            else -> EncoderAcceleration.UNKNOWN
        }
    }

}
