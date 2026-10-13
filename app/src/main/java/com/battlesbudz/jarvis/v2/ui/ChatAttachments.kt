package com.battlesbudz.jarvis.v2.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.chat.*
import kotlinx.coroutines.*

@Composable
internal fun ChatAttachmentPicker(model: LocalModelSpec, enabled: Boolean,
    onBusy: (Boolean) -> Unit, onPrepared: (ChatAttachment) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var kind by rememberSaveable { mutableStateOf(AttachmentKind.IMAGE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val selectedKind = kind
            onBusy(true)
            scope.launch {
                var prepared: ChatAttachment? = null
                var accepted = false
                try {
                    withContext(Dispatchers.IO) { prepared = ChatMediaStore.prepare(context, uri, selectedKind) }
                    onPrepared(requireNotNull(prepared))
                    accepted = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onError(error.message ?: "Could not attach this file.") }
                finally {
                    if (!accepted) prepared?.let { ChatMediaStore.discard(context, it) }
                    onBusy(false)
                }
            }
        }
    }
    if (model.supportsVision) IconButton(enabled = enabled, onClick = {
            kind = AttachmentKind.IMAGE; picker.launch(arrayOf("image/*"))
        }) { ComposerIcon(com.battlesbudz.jarvis.v2.R.drawable.ic_composer_attach, "Attach image") }
}

@Composable
internal fun ChatAttachmentPreview(attachment: ChatAttachment, playbackEnabled: Boolean = true) {
    val thumbnail by produceState<Bitmap?>(null, attachment.uri) {
        if (attachment.kind == AttachmentKind.IMAGE) value = withContext(Dispatchers.IO) {
            runCatching {
                val path = android.net.Uri.parse(attachment.uri).path
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
            }.getOrNull()
        }
    }
    if (attachment.kind == AttachmentKind.AUDIO) VoiceMessagePlayback(attachment, playbackEnabled)
    else thumbnail?.let {
        Image(it.asImageBitmap(), contentDescription = "Attached image", modifier = Modifier.fillMaxWidth().height(120.dp))
    } ?: Text("Image unavailable", style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun VoiceMessagePlayback(attachment: ChatAttachment, enabled: Boolean) {
    val context = LocalContext.current
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var player by remember(attachment.uri) { mutableStateOf<android.media.MediaPlayer?>(null) }
    var playing by remember(attachment.uri) { mutableStateOf(false) }
    var failure by remember(attachment.uri) { mutableStateOf<String?>(null) }
    fun stop() { player?.release(); player = null; playing = false }
    LaunchedEffect(enabled) { if (!enabled) stop() }
    DisposableEffect(attachment.uri, owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(enabled = enabled, onClick = {
            if (playing) stop() else {
                failure = null
                try {
                    val next = android.media.MediaPlayer()
                    player = next
                    next.setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    next.setDataSource(context, android.net.Uri.parse(attachment.uri))
                    next.setOnCompletionListener { stop() }
                    next.setOnErrorListener { _, _, _ -> failure = "Recording unavailable"; stop(); true }
                    next.setOnPreparedListener { if (player === it) it.start() }
                    playing = true
                    next.prepareAsync()
                } catch (_: Exception) { failure = "Recording unavailable"; stop() }
            }
        }) {
            ComposerIcon(if (playing) com.battlesbudz.jarvis.v2.R.drawable.ic_composer_stop
                else com.battlesbudz.jarvis.v2.R.drawable.ic_composer_play,
                if (playing) "Stop playback" else "Play voice message")
        }
        Text(failure ?: "Voice message", style = MaterialTheme.typography.labelMedium)
    }
}
