package com.battlesbudz.jarvis.v2.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

object PhoneCheck {
    fun read(context: Context): PhoneProfile {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        return PhoneProfile("${Build.MANUFACTURER} ${Build.MODEL}", Build.MODEL,
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            memory.totalMem, memory.availMem, StatFs(context.filesDir.path).availableBytes,
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
    }
}
