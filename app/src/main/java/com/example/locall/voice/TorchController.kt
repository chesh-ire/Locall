package com.example.locall.voice

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

private const val TAG = "TorchController"

object TorchController {

    fun set(context: Context, on: Boolean): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return false
        return try {
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run {
                Log.w(TAG, "no camera with a flash unit")
                return false
            }
            cm.setTorchMode(id, on)
            Log.i(TAG, "torch $id -> ${if (on) "on" else "off"}")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setTorchMode failed: ${t.message}")
            false
        }
    }
}
