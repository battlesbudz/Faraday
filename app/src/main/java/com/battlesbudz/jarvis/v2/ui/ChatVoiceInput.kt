package com.battlesbudz.jarvis.v2.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.battlesbudz.jarvis.v2.voice.ChatDictation
import kotlinx.coroutines.*

@Composable
internal fun ChatVoiceInput(enabled: Boolean, canSendAudio: Boolean, audioUnavailableReason: String?, createRecorder: () -> ChatDictation,
    onBusy: (Boolean) -> Unit, onAudio: suspend (ByteArray, String) -> Unit, onTranscript: (String) -> Unit, onError: (String) -> Unit,
    composer: @Composable (@Composable () -> Unit) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    val currentEnabled by rememberUpdatedState(enabled)
    val currentTranscript by rememberUpdatedState(onTranscript)
    val currentAudio by rememberUpdatedState(onAudio)
    val currentError by rememberUpdatedState(onError)
    val currentBusy by rememberUpdatedState(onBusy)
    var job by remember { mutableStateOf<Job?>(null) }
    var recorder by remember { mutableStateOf<ChatDictation?>(null) }
    var recording by remember { mutableStateOf(false) }
    var permissionPending by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var action by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    fun cancel() { job?.cancel() }
    fun start() {
        if (!currentEnabled || job?.isCompleted == false || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val session = createRecorder()
        recorder = session
        recording = true
        finishing = false
        val choice = CompletableDeferred<Boolean>()
        action = choice
        status = "Preparing voice input…"
        currentBusy(true)
        job = scope.launch {
            try {
                val pcm = session.record { update ->
                    scope.launch { if (recorder === session && !finishing) status = update }
                }
                ensureActive()
                if (!choice.isCompleted) status = "Recording ready · Stop for text or Send audio"
                val sendAudio = choice.await()
                val text = session.transcribe(pcm) { update ->
                    scope.launch { if (recorder === session) status = update }
                }
                ensureActive()
                if (sendAudio) { status = "Sending…"; currentAudio(pcm, text) }
                else currentTranscript(text)
            } catch (timeout: TimeoutCancellationException) { currentError("Voice input timed out. Please try again.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { currentError(error.message ?: "Voice input failed. Please try again.") }
            finally {
                recorder = null
                recording = false
                finishing = false
                action = null
                currentBusy(false)
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requestedHere = permissionPending
        permissionPending = false
        if (requestedHere) {
            if (granted) start() else currentError("Microphone permission is needed for voice input.")
        }
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { permissionPending = false; cancel() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); job?.cancel() }
    }
    BackHandler(enabled = recording) { cancel() }
    if (recording) {
        Surface(modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(4.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = { cancel() }, modifier = Modifier.testTag("dictation_cancel")) {
                        ComposerIcon(com.battlesbudz.jarvis.v2.R.drawable.ic_composer_close, "Cancel")
                    }
                    Text(status, modifier = Modifier.weight(1f).testTag("dictation_status"),
                        style = MaterialTheme.typography.bodySmall)
                    IconButton(enabled = !finishing, onClick = {
                        finishing = true; status = "Transcribing…"; action?.complete(false); recorder?.finish()
                    }, modifier = Modifier.testTag("dictation_stop")) {
                        ComposerIcon(com.battlesbudz.jarvis.v2.R.drawable.ic_composer_stop, "Stop")
                    }
                    FilledIconButton(enabled = !finishing && canSendAudio, onClick = {
                        finishing = true; status = "Transcribing…"; action?.complete(true); recorder?.finish()
                    }, modifier = Modifier.testTag("dictation_send")) {
                        ComposerIcon(com.battlesbudz.jarvis.v2.R.drawable.ic_composer_send, "Send")
                    }
                }
                if (!canSendAudio && audioUnavailableReason != null)
                    Text(audioUnavailableReason, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        style = MaterialTheme.typography.bodySmall)
            }
        }
    } else composer {
        IconButton(enabled = enabled && !permissionPending, onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
            else { permissionPending = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
        }, modifier = Modifier.testTag("chat_voice_input")) {
            ComposerIcon(com.battlesbudz.jarvis.v2.R.drawable.ic_composer_mic, "Record voice message")
        }
    }
}

@Composable
internal fun ComposerIcon(resource: Int, description: String) {
    Icon(androidx.compose.ui.res.painterResource(resource), contentDescription = description,
        modifier = Modifier.size(24.dp))
}
