package com.battlesbudz.jarvis.v2.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import com.battlesbudz.jarvis.v2.voice.speaker.*

@Composable
internal fun SpeakerGateSection(enabled: Boolean, controller: SpeakerEnrollmentController? = null) {
    val context = LocalContext.current
    val enrollment = controller ?: remember { SpeakerEnrollmentController.get(context) }
    val state by enrollment.state.collectAsState()
    var permissionNotice by remember { mutableStateOf<String?>(null) }
    var permissionPending by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val shouldRecord = permissionPending
        permissionPending = false
        if (shouldRecord) {
            if (granted) { permissionNotice = null; enrollment.recordPhrase((state.status as? SpeakerEnrollmentStatus.Ready)?.embeddings ?: 0) }
            else permissionNotice = "Microphone permission is needed to record enrollment phrases."
        }
    }
    fun recordPhrase(index: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionNotice = null
            enrollment.recordPhrase(index)
        } else {
            permissionPending = true
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val phrases = listOf("Hey Jarvis, what's on my schedule today?", "Send a message to Andrea saying I'll be home by six.", "Open YouTube and play something I'd like.")
    Column {
        Text("Speaker gate", style = MaterialTheme.typography.titleMedium)
        Text("Faraday only listens to your voice. Enroll three short phrases to turn on speaker verification.", style = MaterialTheme.typography.bodySmall)
        Text(when (val s = state.status) {
            SpeakerEnrollmentStatus.NotEnrolled -> "Not enrolled"
            is SpeakerEnrollmentStatus.Recording -> "Recording phrase ${s.phraseIndex + 1}…"
            is SpeakerEnrollmentStatus.Ready -> "Enrollment ready · ${s.embeddings}/3 phrases recorded"
            is SpeakerEnrollmentStatus.Enrolled -> "Enrolled · ${s.phraseCount} phrases"
            is SpeakerEnrollmentStatus.Error -> "Enrollment error: ${s.message}"
        }, modifier = Modifier.testTag("speaker_gate_status"), style = MaterialTheme.typography.bodySmall)
        when (state.status) {
            SpeakerEnrollmentStatus.NotEnrolled, is SpeakerEnrollmentStatus.Enrolled -> Button(enabled = enabled,
                modifier = Modifier.testTag("speaker_gate_enroll"), onClick = enrollment::startEnrollment) { Text("Enroll voice") }
            else -> Unit
        }
        if (state.status is SpeakerEnrollmentStatus.Ready || state.status is SpeakerEnrollmentStatus.Recording) {
            val count = (state.status as? SpeakerEnrollmentStatus.Ready)?.embeddings ?: 0
            Text(phrases[count.coerceIn(0, 2)], style = MaterialTheme.typography.bodyMedium)
            Button(enabled = enabled && count < 3, modifier = Modifier.testTag("speaker_gate_record_phrase"),
                onClick = { recordPhrase(count) }) { Text("Record phrase ${count + 1}") }
            Button(enabled = enabled && count >= 3, onClick = enrollment::finishEnrollment) { Text("Finish enrollment") }
        }
        if (state.status is SpeakerEnrollmentStatus.Enrolled || state.status !is SpeakerEnrollmentStatus.NotEnrolled)
            TextButton(enabled = enabled, modifier = Modifier.testTag("speaker_gate_delete"), onClick = enrollment::deleteProfile) { Text("Delete voice profile") }
        state.lastMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        permissionNotice?.let { Text(it, modifier = Modifier.testTag("speaker_gate_permission_rationale"),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
