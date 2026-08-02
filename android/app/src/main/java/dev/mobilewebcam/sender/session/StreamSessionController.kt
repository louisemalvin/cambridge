package dev.mobilewebcam.sender.session

import android.view.Surface
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.ReceiverEndpoint
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import kotlinx.coroutines.flow.StateFlow

interface StreamSessionController {
    val state: StateFlow<StreamState>

    suspend fun start(
        endpoint: ReceiverEndpoint,
        preference: CodecPreference,
        profile: VideoProfile,
        previewSurface: Surface?,
    ): Result<Unit>

    suspend fun stop(): Result<Unit>

    suspend fun updateBitrate(bitrateBps: Int): Result<Unit>
}
