package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence
import kotlinx.coroutines.*
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Call evidence is supplied by composition; rendering has no authority to locate the runtime. */
internal data class CallEvidenceSnapshot(val report: String, val audio: LiveCallAudioEvidence.Export?)
internal data class CallEvidenceActions(
    val armAudio: () -> Unit,
    val clearAudio: () -> Unit,
    val snapshot: () -> CallEvidenceSnapshot,
)

/** Snapshot the latest call report and optional bounded audio sample before opening the document picker. */
@Composable
internal fun CallEvidenceExport(actions: CallEvidenceActions, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    var pendingAudio by remember { mutableStateOf<LiveCallAudioEvidence.Export?>(null) }
    var status by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val report = pending
        val audio = pendingAudio
        pending = null
        pendingAudio = null
        if (uri != null && report != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    writeCallEvidenceZip(CallEvidenceSnapshot(report, audio),
                        checkNotNull(context.contentResolver.openOutputStream(uri)))
                }
                status = "Call test ZIP saved."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { status = "Save failed: ${error.message}" }
        }
    }
    Text("Diagnostic audio is off by default. Recording retains a bounded sample in memory, including nearby speech: up to 800 KB per stream and 4 MB total. Older audio may be dropped; this is not a whole-call recording. Audio is only written to a file when you export.", style = MaterialTheme.typography.bodySmall)
    TextButton(enabled = enabled, onClick = {
        actions.armAudio()
        pending = null
        pendingAudio = null
        status = "The next call will retain a bounded audio sample in memory. Export it after ending the call."
    }) { Text("Record next call audio for diagnosis") }
    TextButton(enabled = enabled, onClick = {
        actions.clearAudio()
        pending = null
        pendingAudio = null
        status = "Diagnostic audio cleared; recording disarmed."
    }) { Text("Clear diagnostic audio") }
    TextButton(enabled = enabled, onClick = {
        val snapshot = actions.snapshot()
        pendingAudio = snapshot.audio
        pending = snapshot.report
        try {
            save.launch("jarvis-call-echo-${System.currentTimeMillis()}.zip")
        } catch (error: Exception) {
            pending = null
            pendingAudio = null
            status = "Save failed: ${error.message}"
        }
    }) { Text("Save latest call test ZIP") }
    if (status.isNotEmpty()) Text(status)
}

/** The export contains only the frozen, explicitly requested sample, never an implicit recording. */
internal fun writeCallEvidenceZip(snapshot: CallEvidenceSnapshot, output: OutputStream) {
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("report.txt"))
        zip.write(snapshot.report.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        snapshot.audio?.let { audio ->
            zip.putNextEntry(ZipEntry("audio-evidence.txt"))
            zip.write(audio.report.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            audio.files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
    }
}
