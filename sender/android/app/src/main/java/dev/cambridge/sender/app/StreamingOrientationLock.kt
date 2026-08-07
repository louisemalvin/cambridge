package dev.cambridge.sender.app

import android.app.Activity
import android.content.pm.ActivityInfo
import dev.cambridge.sender.model.StreamOrientation

fun Activity.lockStreamingOrientation(orientation: StreamOrientation) {
    requestedOrientation = when (orientation) {
        StreamOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        StreamOrientation.LANDSCAPE_REVERSED -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        StreamOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        StreamOrientation.PORTRAIT_REVERSED -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    }
}

fun Activity.unlockStreamingOrientation() {
    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}
