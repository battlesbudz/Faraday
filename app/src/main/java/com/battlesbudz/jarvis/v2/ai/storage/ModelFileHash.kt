package com.battlesbudz.jarvis.v2.ai.storage

import java.io.File
import java.security.MessageDigest

internal fun File.sha256(
    onProgress: (processedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val totalBytes = length()
        var processedBytes = 0L
        var lastReportedBytes = -1L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
        var count: Int
        while (input.read(buffer).also { count = it } >= 0) {
            if (count > 0) {
                digest.update(buffer, 0, count)
                processedBytes += count
                if (processedBytes == totalBytes ||
                    lastReportedBytes < 0L ||
                    processedBytes - lastReportedBytes >= 1L * 1024L * 1024L
                ) {
                    lastReportedBytes = processedBytes
                    onProgress(processedBytes, totalBytes)
                }
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
