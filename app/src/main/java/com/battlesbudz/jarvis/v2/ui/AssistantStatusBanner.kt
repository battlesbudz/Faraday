package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.assistant.JarvisInteractionService

/**
 * One-time banner shown when Jarvis is not the system-selected assistant.
 * Explains that background app launches need the assistant binding, with a
 * button to open system assistant settings. Dismissal is persisted so it
 * only nags once per install.
 *
 * The banner must be placed in the layout flow (e.g. as the first child of a
 * Column), never as an overlay Box on top of the content: as the first child
 * of a Box it draws BEHIND the full-size content, so the screen's own text
 * renders on top of the banner and everything becomes unreadable.
 */
@Composable
fun AssistantStatusBanner() {
    val context = LocalContext.current
    var show by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("jarvis_assistant_status", Context.MODE_PRIVATE)
        val dismissed = prefs.getBoolean("banner_dismissed", false)
        if (!dismissed && !JarvisInteractionService.isSelected(context)) {
            show = true
        }
    }

    if (!show) return

    AssistantStatusBannerCard(
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        },
        onDismiss = {
            context.getSharedPreferences("jarvis_assistant_status", Context.MODE_PRIVATE)
                .edit().putBoolean("banner_dismissed", true).apply()
            show = false
        }
    )
}

/**
 * The presentational banner card, without the SharedPreferences /
 * assistant-service gating, so readability tests can mount it directly.
 */
@Composable
fun AssistantStatusBannerCard(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Faraday is not your system assistant",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Opening apps while Faraday is in the background needs the " +
                    "system assistant binding. Set Faraday as your default " +
                    "assistant to make background launches work reliably.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Open assistant settings")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text("Dismiss")
                }
            }
        }
    }
}
