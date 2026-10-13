package com.battlesbudz.jarvis.v2.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.chat.ConversationThread
import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.diagnostics.ConversationMetricsExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Copy is explicit, available after End, and never changes microphone/model ownership. */
@Composable
internal fun ConversationMetricsControls(thread: ConversationThread, store: AndroidPipelineBenchmarkStore,
    onOpen: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copying by remember { mutableStateOf(false) }
    var status by remember(thread.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row {
            TextButton(onClick = onOpen, modifier = Modifier.testTag("conversation_metrics_open")) { Text("Metrics") }
            TextButton(enabled = !copying, modifier = Modifier.testTag("conversation_metrics_copy"), onClick = {
                val snapshot = thread
                scope.launch {
                    copying = true
                    try {
                        val payload = withContext(Dispatchers.Default) {
                            ConversationMetricsExport.json(store.report(conversationId = snapshot.id), snapshot).toString()
                        }
                        if (payload.toByteArray(Charsets.UTF_8).size > 400_000) {
                            status = "Report too large to copy. Open Metrics to save or share the full report."
                        } else {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("Jarvis conversation metrics", payload))
                            status = "Conversation metrics copied."
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "Could not copy metrics. Open Metrics to save or share." }
                    finally { copying = false }
                }
            }) { Text(if (copying) "Copying…" else "Copy metrics") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("conversation_metrics_status"))
    }
}
