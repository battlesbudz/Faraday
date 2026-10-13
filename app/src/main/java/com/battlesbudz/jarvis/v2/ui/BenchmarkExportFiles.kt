package com.battlesbudz.jarvis.v2.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

internal enum class BenchmarkExportFormat(val extension: String, val mime: String) {
    TXT("txt", "text/plain"), JSON("json", "application/json"), CSV("csv", "text/csv")
}

/** Android transport only. The caller owns the frozen payload and explicit user action. */
internal object BenchmarkExportFiles {
    fun filename(format: BenchmarkExportFormat): String = "jarvis-pipeline-benchmarks-${UUID.randomUUID()}.${format.extension}"

    fun createDocument(format: BenchmarkExportFormat): ActivityResultContracts.CreateDocument =
        object : ActivityResultContracts.CreateDocument(format.mime) {
            override fun createIntent(context: Context, input: String): Intent =
                // Exports need a stream-backed destination for ContentResolver.openOutputStream.
                super.createIntent(context, input).addCategory(Intent.CATEGORY_OPENABLE)
        }

    fun save(context: Context, uri: Uri, payload: String) {
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Destination is not writable" }
            .use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }

    fun share(context: Context, format: BenchmarkExportFormat, payload: String): Intent {
        val directory = File(context.cacheDir, "benchmark-exports")
        check(directory.isDirectory || directory.mkdirs())
        // A new share must not evict the preceding share while its receiver still reads it.
        val expiry = System.currentTimeMillis() - 24L * 60 * 60 * 1000
        directory.listFiles()?.filter { it.isFile && it.lastModified() < expiry }?.forEach { it.delete() }
        val file = File(directory, filename(format))
        file.writeText(payload, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.benchmark-exports", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, file.name)
            clipData = ClipData.newRawUri("Jarvis pipeline benchmark report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
