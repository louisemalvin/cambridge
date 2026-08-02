package dev.mobilewebcam.sender.capabilities.mediacodec

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import dev.mobilewebcam.sender.capabilities.EncoderCapabilityProbe
import dev.mobilewebcam.sender.model.EncoderAcceleration
import dev.mobilewebcam.sender.model.EncoderCapability
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
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
        return VideoCodec.entries.flatMap { codec ->
            profiles.map { profile ->
                probeProfile(codecInfos, codec, profile)
            }
        }
    }

    private fun probeProfile(
        codecInfos: Array<MediaCodecInfo>,
        codec: VideoCodec,
        profile: VideoProfile,
    ): EncoderCapability {
        val matchingEncoders = codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any {
                it.equals(codec.mediaCodecMimeType, ignoreCase = true)
            }
        }
        if (matchingEncoders.isEmpty()) {
            return unsupported(codec, profile, "No encoder advertises " + codec.mediaCodecMimeType)
        }
        val candidates = matchingEncoders.mapNotNull { info ->
            val capabilities = runCatching {
                info.getCapabilitiesForType(codec.mediaCodecMimeType)
            }.getOrNull() ?: return@mapNotNull null
            val video = capabilities.videoCapabilities ?: return@mapNotNull null
            val supported = video.areSizeAndRateSupported(
                profile.width,
                profile.height,
                profile.fps.toDouble(),
            )
            EncoderCapability(
                codec = codec,
                profileId = profile.id,
                supported = supported,
                acceleration = acceleration(info),
                encoderName = info.name,
                reason = if (supported) null else "Resolution or frame rate is outside codec capabilities",
            )
        }
        return candidates
            .sortedWith(compareByDescending<EncoderCapability> { it.supported }
                .thenByDescending { it.acceleration == EncoderAcceleration.HARDWARE })
            .firstOrNull()
            ?: unsupported(codec, profile, "Encoder capability metadata was unavailable")
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

    private fun unsupported(
        codec: VideoCodec,
        profile: VideoProfile,
        reason: String,
    ) = EncoderCapability(
        codec = codec,
        profileId = profile.id,
        supported = false,
        acceleration = EncoderAcceleration.UNKNOWN,
        encoderName = null,
        reason = reason,
    )
}
