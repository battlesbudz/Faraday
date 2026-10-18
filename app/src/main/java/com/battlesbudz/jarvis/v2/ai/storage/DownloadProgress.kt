package com.battlesbudz.jarvis.v2.ai.storage

/**
 * Pure helpers for rendering model-download progress. No Android dependencies,
 * so they are unit-testable on the JVM and reusable from workers and UI alike.
 */

/** Rolling byte-rate estimate over recent (timestampMs, cumulativeBytes) samples. */
class DownloadSpeedTracker(
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = 8_000L,
    private val maxSamples: Int = 40,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** Records a cumulative byte count; returns the current estimated bytes/sec. */
    fun addSample(bytes: Long): Double {
        val now = clockMs()
        val last = samples.lastOrNull()
        if (last != null && bytes < last.second) samples.clear() // a new transfer started
        samples.addLast(now to bytes)
        while (samples.size > maxSamples) samples.removeFirst()
        val cutoff = now - windowMs
        while (samples.size > 1 && samples.first().first < cutoff) samples.removeFirst()
        return speedBytesPerSec
    }

    val speedBytesPerSec: Double
        get() {
            if (samples.size < 2) return 0.0
            val (t0, b0) = samples.first()
            val (t1, b1) = samples.last()
            val dtSec = (t1 - t0) / 1000.0
            if (dtSec <= 0.0 || b1 <= b0) return 0.0
            return (b1 - b0) / dtSec
        }

    fun reset() {
        samples.clear()
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0L) return "?"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes B"
    else "%.1f %s".format(java.util.Locale.US, value, units[unit])
}

fun formatEta(remainingBytes: Long, speedBytesPerSec: Double): String? {
    if (speedBytesPerSec <= 0.0 || remainingBytes <= 0L) return null
    val seconds = (remainingBytes / speedBytesPerSec).toLong()
    return when {
        seconds < 60L -> "about $seconds sec left"
        seconds < 3600L -> "about ${seconds / 60L} min left"
        else -> "over an hour left"
    }
}

/**
 * One-line human summary, e.g. "42% · 1.2 GB of 2.9 GB · 8.4 MB/s · about 3 min left".
 * Falls back gracefully when the total size or the speed is unknown.
 */
fun downloadProgressLabel(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Double): String {
    val done = formatBytes(downloadedBytes.coerceAtLeast(0L))
    if (totalBytes <= 0L) {
        return if (speedBytesPerSec > 0.0) "Downloading… $done so far · ${formatBytes(speedBytesPerSec.toLong())}/s"
        else "Downloading… $done so far"
    }
    val percent = ((downloadedBytes.coerceAtLeast(0L) * 100L) / totalBytes).coerceIn(0L, 100L)
    val parts = mutableListOf("$percent% · $done of ${formatBytes(totalBytes)}")
    if (speedBytesPerSec > 0.0) parts += "${formatBytes(speedBytesPerSec.toLong())}/s"
    formatEta((totalBytes - downloadedBytes).coerceAtLeast(0L), speedBytesPerSec)?.let { parts += it }
    return parts.joinToString(" · ")
}
