package dev.mobilewebcam.sender.app

import android.app.Activity
import android.content.pm.ActivityInfo
import dev.mobilewebcam.sender.model.StreamOrientation

fun Activity.lockStreamingOrientation(orientation: StreamOrientation) {
    requestedOrientation = if (orientation.isPortrait) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

fun Activity.unlockStreamingOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
