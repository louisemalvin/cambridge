package dev.mobilewebcam.sender.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import dev.mobilewebcam.sender.model.StreamOrientation

fun Activity.lockStreamingOrientation(orientation: StreamOrientation) {
    requestedOrientation = if (orientation.isPortrait) {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    } else {
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

fun Activity.lockStreamingOrientation(configuration: Int) {
    lockStreamingOrientation(
        if (configuration == Configuration.ORIENTATION_PORTRAIT) {
            StreamOrientation.PORTRAIT
        } else {
            StreamOrientation.LANDSCAPE
        },
    )
}

fun Activity.unlockStreamingOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
