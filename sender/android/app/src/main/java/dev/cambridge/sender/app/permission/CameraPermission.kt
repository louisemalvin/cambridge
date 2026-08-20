package dev.cambridge.sender.app.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

data class CameraPermissionSnapshot(
    val isGranted: Boolean,
    val hasBeenDenied: Boolean,
    val isPermanentlyDenied: Boolean,
)

class CameraPermissionTracker(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val applicationContext = context.applicationContext

    fun read(activity: Activity?): CameraPermissionSnapshot {
        val isGranted = applicationContext.hasCameraPermission()
        val requestedBefore = preferences.getBoolean(REQUESTED_BEFORE_KEY, false)
        val isPermanentlyDenied = !isGranted && activity?.cameraPermissionIsPermanentlyDenied(
            requestedBefore,
        ) == true
        return CameraPermissionSnapshot(
            isGranted = isGranted,
            hasBeenDenied = !isGranted && preferences.getBoolean(DENIED_KEY, false),
            isPermanentlyDenied = isPermanentlyDenied,
        )
    }

    fun recordRequest() {
        preferences.edit()
            .putBoolean(REQUESTED_BEFORE_KEY, true)
            .apply()
    }

    fun recordResult(granted: Boolean) {
        preferences.edit()
            .putBoolean(REQUESTED_BEFORE_KEY, true)
            .putBoolean(DENIED_KEY, !granted)
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "camera-permission"
        const val REQUESTED_BEFORE_KEY = "requested-before"
        const val DENIED_KEY = "denied"
    }
}

fun Context.hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.CAMERA,
) == PackageManager.PERMISSION_GRANTED

fun Activity.cameraPermissionIsPermanentlyDenied(requestedBefore: Boolean): Boolean =
    requestedBefore && !ActivityCompat.shouldShowRequestPermissionRationale(
        this,
        Manifest.permission.CAMERA,
    )
