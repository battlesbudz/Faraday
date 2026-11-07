package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The chat header, moved beside the wisp (approved layout, 5 November 2026).
 *
 * The six header actions used to stack in three rows under the wisp
 * presence, pushing the transcript down. They now flank the presence in
 * the app frame's top band: Memory / Conversations / Metrics on the left,
 * Settings / Private / New on the right, in the same Material3 TextButton
 * style as the old rows. The band is stateless: JarvisApp owns the state
 * and hands the screen's own callbacks in, so every action keeps its exact
 * previous behavior and destination. The side columns share equal weight
 * and the presence takes the middle share, which keeps the wisp exactly
 * centered on screen.
 *
 * Labels keep the old second row's narrow-screen contract: one line,
 * ellipsized visually, full text in semantics.
 */
@Composable
internal fun ChatHeaderBand(
    memoryEnabled: Boolean,
    settingsEnabled: Boolean,
    conversationsEnabled: Boolean,
    privateEnabled: Boolean,
    newEnabled: Boolean,
    metricsVisible: Boolean,
    onOpenMemory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewPrivateConversation: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenMetrics: () -> Unit,
    presence: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            TextButton(enabled = memoryEnabled, onClick = onOpenMemory,
                modifier = Modifier.testTag("memory_open")) {
                Text("Memory", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = conversationsEnabled, onClick = onOpenHistory) {
                Text("Conversations", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            if (metricsVisible) {
                TextButton(onClick = onOpenMetrics,
                    modifier = Modifier.testTag("conversation_metrics_open")) {
                    Text("Metrics", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.weight(2f)) { presence() }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            TextButton(enabled = settingsEnabled, onClick = onOpenSettings) {
                Text("Settings", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = privateEnabled, onClick = onNewPrivateConversation,
                modifier = Modifier.testTag("new_private_chat")) {
                Text("Private", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            TextButton(enabled = newEnabled, onClick = onNewConversation) {
                Text("New", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
