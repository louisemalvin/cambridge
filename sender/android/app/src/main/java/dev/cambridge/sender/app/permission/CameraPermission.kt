package dev.cambridge.sender.app.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

fun Context.hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(
    this,
    Manifest.permission.CAMERA,
) == PackageManager.PERMISSION_GRANTED

fun Activity.cameraPermissionIsPermanentlyDenied(requestedBefore: Boolean): Boolean =
    requestedBefore && !ActivityCompat.shouldShowRequestPermissionRationale(
        this,
        Manifest.permission.CAMERA,
    )
