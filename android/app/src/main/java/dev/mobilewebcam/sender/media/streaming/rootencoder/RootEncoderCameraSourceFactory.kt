package dev.mobilewebcam.sender.media.streaming.rootencoder

import android.content.Context
import com.pedro.extrasources.CameraXSource

class RootEncoderCameraSourceFactory(
    private val context: Context,
) {
    fun createCameraXSource(): CameraXSource {
        return CameraXSource(context)
    }
}
