package com.battlesbudz.jarvis.v2.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.diagnostics.TurnLatency

@Composable
internal fun TurnLatencyFooter(latency: TurnLatency) {
    var expanded by remember(latency.id) { mutableStateOf(false) }
    var copied by remember(latency.id) { mutableStateOf(false) }
    val context = LocalContext.current
    Column {
        TextButton(onClick = { expanded = !expanded }) {
            Text(latency.summary(), style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) {
            Text(latency.details(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Jarvis reply latency", latency.details() + "\n\n" + latency.json().toString(2)))
                copied = true
            }) { Text(if (copied) "Copied this reply" else "Copy this reply’s timings") }
        }
    }
}
