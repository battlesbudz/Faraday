package com.battlesbudz.jarvis.v2.diagnostics

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

/**
 * "Save diagnostics" writes the JSON export to the phone's Downloads folder.
 *
 * The first version of this button only opened a share chooser: nothing was
 * ever saved anywhere, and the file could not be pulled back off the phone
 * afterwards (Battles's device report on build 1666). The button now does
 * what its name says — a MediaStore Downloads entry the Files app shows —
 * and reports the written file (or the failure) inline.
 */
internal object DiagnosticsDownloadsSave {
    const val MIME_TYPE: String = "application/json"

    /**
     * Where one save lands. Production writes through MediaStore; the JVM
     * tests substitute a fake so the naming, content and failure reporting
     * are pinned without a device.
     */
    fun interface Destination {
        /** Writes [payload] as a new entry named [displayName]; returns the name it was saved under. */
        fun write(displayName: String, mimeType: String, payload: ByteArray): String
    }

    sealed interface Outcome {
        data class Saved(val fileName: String) : Outcome
        data class Failed(val reason: String) : Outcome
    }

    /** The line the diagnostics section shows after a save attempt; it stays until the next action. */
    fun message(outcome: Outcome): String = when (outcome) {
        is Outcome.Saved -> "Saved ${outcome.fileName} to Downloads"
        is Outcome.Failed -> "Save diagnostics failed: ${outcome.reason}"
    }

    fun fileName(buildNumber: String, nowMs: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        return "faraday-diagnostics-build$buildNumber-$stamp.json"
    }

    /**
     * The exact text the save writes: the Copy diagnostics content as a
     * structured JSON envelope around [DiagnosticRecorder.snapshotJson].
     */
    fun payload(recorder: DiagnosticRecorder, microphoneHandoff: String, generatedAtMs: Long): String =
        JSONObject().apply {
            put("title", "Jarvis OS V2 — latest Voice Call diagnostics")
            put("generatedAtMs", generatedAtMs)
            put("diagnostics", recorder.snapshotJson())
            put("microphoneHandoff", microphoneHandoff)
        }.toString(2)

    fun save(destination: Destination, buildNumber: String, payload: String, nowMs: Long): Outcome =
        try {
            Outcome.Saved(destination.write(fileName(buildNumber, nowMs), MIME_TYPE, payload.toByteArray(Charsets.UTF_8)))
        } catch (error: Exception) {
            Outcome.Failed(error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName)
        }

    /**
     * The Downloads folder through the MediaStore Downloads collection.
     * Needs no storage permission on API 29+ (the app's minSdk is 30), and
     * the entry is visible in the Files app under Downloads. A write that
     * fails part-way deletes its row: a truncated export must not sit in
     * Downloads looking complete.
     */
    fun downloads(context: Context): Destination = Destination { displayName, mimeType, payload ->
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads refused the new file")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("Downloads refused the file contents")
            stream.use { it.write(payload) }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
        displayName
    }
}
