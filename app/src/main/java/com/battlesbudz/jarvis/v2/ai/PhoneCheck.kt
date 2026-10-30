package com.battlesbudz.jarvis.v2.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs

/** API level that added ActivityManager.MemoryInfo.advertisedMem (Android 14). */
private const val ADVERTISED_MEM_API = 34

object PhoneCheck {
    fun read(context: Context): PhoneProfile {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val advertised = if (Build.VERSION.SDK_INT >= ADVERTISED_MEM_API) memory.advertisedMem else 0L
        return PhoneProfile("${Build.MANUFACTURER} ${Build.MODEL}", Build.MODEL,
            if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            advertisedRamBytes(Build.VERSION.SDK_INT, advertised, memory.totalMem),
            memory.availMem, StatFs(context.filesDir.path).availableBytes,
            Build.SUPPORTED_ABIS.any { it == "arm64-v8a" })
    }
}

/**
 * The RAM figure memory advisories should judge a phone by. Android's
 * totalMem reports usable RAM, which sits below the size the phone was sold
 * with (an "8 GB" phone reports roughly 6.9 GiB), so advisories keyed to it
 * can misjudge the phone — a bundle sized for the marketed figure looks
 * tighter than it is. Android 14+ reports the advertised size directly;
 * below that, snap the reported figure up to the nearest marketed size. An
 * advertised figure that is missing or non-positive falls back to the snap.
 */
internal fun advertisedRamBytes(sdkInt: Int, advertisedMemBytes: Long, totalMemBytes: Long): Long =
    if (sdkInt >= ADVERTISED_MEM_API && advertisedMemBytes > 0L) advertisedMemBytes
    else snapUpToMarketedRamSize(totalMemBytes)

/** Marketed RAM sizes, ascending. */
private val MARKETED_RAM_BYTES: List<Long> =
    listOf(1L, 2L, 3L, 4L, 6L, 8L, 12L, 16L, 24L, 32L, 48L, 64L)
        .map { it * 1024L * 1024L * 1024L }

internal fun snapUpToMarketedRamSize(totalMemBytes: Long): Long {
    if (totalMemBytes <= 0L) return totalMemBytes
    return MARKETED_RAM_BYTES.firstOrNull { it >= totalMemBytes } ?: totalMemBytes
}
