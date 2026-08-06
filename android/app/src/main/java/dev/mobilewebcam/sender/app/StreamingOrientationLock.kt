package dev.mobilewebcam.sender.app

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration

fun Activity.lockStreamingOrientation(configuration: Int) {
    requestedOrientation = when (configuration) {
        Configuration.ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

fun Activity.unlockStreamingOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
