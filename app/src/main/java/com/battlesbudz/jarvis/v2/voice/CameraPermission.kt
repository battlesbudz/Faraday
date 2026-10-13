package com.battlesbudz.jarvis.v2.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Camera permission plumbing. The system prompt can only be triggered from
 * an Activity; services must check and degrade. Slice 1 degrades to
 * audio-only when denied (see [CallVisionController.State.DENIED]).
 */
object CameraPermission {
    const val REQUEST_CODE = 1401

    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Trigger the system permission prompt. Must be called from an Activity;
     * the result lands in onRequestPermissionsResult with [REQUEST_CODE].
     */
    fun request(activity: Activity) {
        activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CODE)
    }
}
