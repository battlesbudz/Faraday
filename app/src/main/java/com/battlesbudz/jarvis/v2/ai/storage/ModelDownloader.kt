package com.battlesbudz.jarvis.v2.ai.storage

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

/** Resumable HTTP transfer only; the store owns identity, hashes and atomic installation. */
internal class ModelDownloader(
    private val parallelThreshold: Long = 128L * 1024L * 1024L
) {
    private companion object {
        const val PARALLEL_CHUNKS = 6
    }
    /**
     * Downloads large model files using resumable HTTP ranges. Several ranges
     * are fetched concurrently when the host supports Range requests; each
     * range has its own checkpoint file so an interrupted setup resumes without
     * discarding completed work.
     */
    suspend fun download(
        url: String,
        temporary: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onStatus: (String) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val totalBytes = discoverDownloadSize(url)
        if (totalBytes > 0L && temporary.length() == totalBytes) {
            onProgress(totalBytes, totalBytes)
            return@withContext
        }
        if (totalBytes <= parallelThreshold) {
            downloadSingleStream(url, temporary, totalBytes, onProgress)
            return@withContext
        }

        val chunkDirectory = File(temporary.parentFile, "${temporary.name}.chunks")
        val chunkSize = (totalBytes + PARALLEL_CHUNKS - 1L) / PARALLEL_CHUNKS
        val progressLock = Any()
        val completedBytes = AtomicLong(0L)
        val checkpoint = File(chunkDirectory, "source")
        val identity = "$url\n$totalBytes\n$PARALLEL_CHUNKS"
        if (checkpoint.takeIf { it.isFile }?.readText() != identity) chunkDirectory.deleteRecursively()
        check(chunkDirectory.isDirectory || chunkDirectory.mkdirs()) { "Could not create model download storage." }
        checkpoint.writeText(identity)
        chunkDirectory.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { file ->
            val index = file.name.removeSuffix(".part").toIntOrNull()
            val expected = if (index != null && index in 0 until PARALLEL_CHUNKS)
                minOf(chunkSize, (totalBytes - index * chunkSize).coerceAtLeast(0L)) else 0L
            if (expected == 0L || file.length() > expected) file.delete()
            else completedBytes.addAndGet(file.length())
        }
        // Keep a complete assembled file for verification after process death.
        // Do not discard saved chunks on failure/cancellation.
        onStatus("Downloading the AI model in $PARALLEL_CHUNKS resumable parts…")
        onProgress(completedBytes.get(), totalBytes)

        coroutineScope {
            (0 until PARALLEL_CHUNKS).map { index ->
                async(Dispatchers.IO) {
                    val start = index * chunkSize
                    if (start >= totalBytes) return@async
                    val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                    val part = File(chunkDirectory, "$index.part")
                    val expected = end - start + 1L
                    if (part.length() < expected) {
                        downloadRange(
                            url = url,
                            start = start + part.length(),
                            end = end,
                            totalBytes = totalBytes,
                            part = part,
                            onBytes = { count ->
                                val current = completedBytes.addAndGet(count)
                                synchronized(progressLock) { onProgress(current, totalBytes) }
                            }
                        )
                    }
                    check(part.length() == expected) { "Model download part $index is incomplete." }
                }
            }.awaitAll()
        }
        onStatus("Assembling the downloaded AI model…")
        FileOutputStream(temporary).use { output ->
            for (index in 0 until PARALLEL_CHUNKS) {
                currentCoroutineContext().ensureActive()
                val start = index * chunkSize
                if (start >= totalBytes) break
                val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
                val part = File(chunkDirectory, "$index.part")
                check(part.length() == end - start + 1L) { "Model download part $index is incomplete." }
                part.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 16) }
            }
            output.fd.sync()
        }
        check(temporary.length() == totalBytes) { "Model download size is incorrect." }
        chunkDirectory.deleteRecursively()
        Unit
    }

    private suspend fun discoverDownloadSize(url: String): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val disconnectOnCancel = disconnectOnCancellation(connection)
        return try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_PARTIAL) return -1L
            val range = connection.getHeaderField("Content-Range") ?: return -1L
            range.substringAfterLast("/").toLongOrNull() ?: -1L
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            disconnectOnCancel.cancel()
            connection.disconnect()
        }
    }

    private suspend fun downloadSingleStream(
        url: String,
        temporary: File,
        totalBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val existingBytes = temporary.length()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val disconnectOnCancel = disconnectOnCancellation(connection)
        try {
            val responseCode = connection.responseCode
            val append = existingBytes > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
            check(responseCode in 200..299) { "Model download failed with HTTP $responseCode." }
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                val range = parseRange(connection.getHeaderField("Content-Range"))
                check(range.first == if (append) existingBytes else 0L) { "The model host returned the wrong byte range." }
                check(totalBytes <= 0L || range.third == totalBytes) { "The model size changed during download." }
            }
            val startingBytes = if (append) existingBytes else 0L
            if (!append && existingBytes > 0L) temporary.delete()
            val resolvedTotal = totalBytes.takeIf { it > 0L }
                ?: connection.contentLengthLong.takeIf { it > 0L }?.let { it + startingBytes }
                ?: -1L
            var downloadedBytes = startingBytes
            connection.inputStream.use { input ->
                FileOutputStream(temporary, append).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        currentCoroutineContext().ensureActive()
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        downloadedBytes += count
                        check(resolvedTotal <= 0L || downloadedBytes <= resolvedTotal) { "Model download exceeds its expected size." }
                        onProgress(downloadedBytes, resolvedTotal)
                    }
                    output.fd.sync()
                }
            }
            check(resolvedTotal <= 0L || downloadedBytes == resolvedTotal) { "The model download is incomplete. Retry to resume it." }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            disconnectOnCancel.cancel()
            connection.disconnect()
        }
    }

    private suspend fun downloadRange(
        url: String,
        start: Long,
        end: Long,
        totalBytes: Long,
        part: File,
        onBytes: (Long) -> Unit
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 5_000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=$start-$end")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val disconnectOnCancel = disconnectOnCancellation(connection)
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "The model host does not support resumable range downloads."
            }
            val range = parseRange(connection.getHeaderField("Content-Range"))
            check(range.first == start && range.second == end && range.third == totalBytes) { "The model host returned the wrong byte range." }
            val expectedBytes = end - start + 1L
            var received = 0L
            part.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(part, true).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    var count: Int
                    while (input.read(buffer).also { count = it } >= 0) {
                        currentCoroutineContext().ensureActive()
                        if (count == 0) continue
                        received += count
                        check(received <= expectedBytes) { "The model host returned too many bytes." }
                        output.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                    output.fd.sync()
                }
            }
            check(received == expectedBytes) { "The model download is incomplete. Retry to resume it." }
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            disconnectOnCancel.cancel()
            connection.disconnect()
        }
    }

    private suspend fun disconnectOnCancellation(connection: HttpURLConnection): Job =
        CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }

    private fun parseRange(value: String?): Triple<Long, Long, Long> {
        val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(value.orEmpty())
            ?: error("The model host returned an invalid byte range.")
        return Triple(match.groupValues[1].toLong(), match.groupValues[2].toLong(), match.groupValues[3].toLong())
    }

}
