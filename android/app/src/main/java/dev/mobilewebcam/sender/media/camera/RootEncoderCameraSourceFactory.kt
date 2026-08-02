package dev.mobilewebcam.sender.media.camera

import android.content.Context
import com.pedro.extrasources.CameraXSource

class RootEncoderCameraSourceFactory(
    private val context: Context,
) {
    fun createCameraXSource(): CameraXSource {
        return CameraXSource(context)
    }
}
