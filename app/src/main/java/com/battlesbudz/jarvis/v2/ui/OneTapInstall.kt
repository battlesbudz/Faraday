package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First-run screen: one "Install and start" button that downloads the default
 * model (stock Gemma-4-E2B-it) and hands off to the normal app flow once it is
 * installed and its automatic check passes. Shown only when no usable model
 * exists on the device, so there is nothing to configure before the tap.
 *
 * While the transfer runs, the user owns it: Pause stops the work and keeps
 * the downloaded progress on the phone (the paused state below offers
 * Resume), and Cancel — behind a confirmation, because it is destructive —
 * stops the work and removes that saved progress. Neither control existed
 * here before; the only way out of a download was waiting for it to finish.
 */
@Composable
internal fun OneTapInstall(
    downloading: Boolean,
    testing: Boolean,
    importing: Boolean,
    downloadBytes: Long,
    downloadTotalBytes: Long,
    status: String,
    paused: Boolean,
    partialBytes: Long,
    onInstall: () -> Unit,
    onChooseDifferentModel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    if (confirmingDiscard) {
        val savedBytes = if (downloading) downloadBytes else partialBytes
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Cancel this download?") },
            text = {
                Text(
                    "This stops the download and removes the " +
                        "${oneTapMegabytes(savedBytes)} MB already saved on this phone. " +
                        "Pausing instead keeps that progress, so the download can resume where it left off."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmingDiscard = false; onDiscard() },
                    modifier = Modifier.testTag("one_tap_confirm_discard")
                ) { Text("Cancel download") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text("Keep it") }
            }
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Faraday", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Your private on-device assistant. Tap below to download the AI model and get started.",
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            textAlign = TextAlign.Center
        )
        if (downloading || testing) {
            if (downloadTotalBytes > 0L) {
                val progress = (downloadBytes.toFloat() / downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${oneTapMegabytes(downloadBytes)} / ${oneTapMegabytes(downloadTotalBytes)} MB " +
                        "(${(progress * 100).toInt()}%)",
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (status.isNotBlank()) {
                Text(status, modifier = Modifier.padding(top = 20.dp), textAlign = TextAlign.Center)
            }
            if (downloading) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onPause,
                        modifier = Modifier.weight(1f).testTag("one_tap_pause")
                    ) { Text("Pause") }
                    TextButton(
                        onClick = { confirmingDiscard = true },
                        modifier = Modifier.weight(1f).testTag("one_tap_cancel_download")
                    ) { Text("Cancel download") }
                }
                Text(
                    "Pause keeps the progress saved on this phone. Cancel removes it.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else if (paused) {
            Text("Download paused", style = MaterialTheme.typography.titleMedium)
            Text(
                "${oneTapMegabytes(partialBytes)} MB is saved on this phone. " +
                    "Resume to pick up where the download left off, or cancel to remove it.",
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onResume,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth().testTag("one_tap_resume")
            ) {
                Text("Resume download")
            }
            TextButton(
                onClick = { confirmingDiscard = true },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth().testTag("one_tap_cancel_download")
            ) {
                Text("Cancel download")
            }
            if (status.isNotBlank()) {
                Text(status, modifier = Modifier.padding(top = 20.dp), textAlign = TextAlign.Center)
            }
        } else {
            // Same gating as the ModelSetup screen: never start a transfer while
            // another model operation (download / test / import) is in progress.
            Button(
                onClick = onInstall,
                enabled = !downloading && !testing && !importing,
                modifier = Modifier.fillMaxWidth().testTag("one_tap_install")
            ) {
                Text("Install and start")
            }
            // Escape hatch to the full setup screen (model browser / import)
            // for anyone who does not want the default model. Idle state
            // only: this branch is not composed while a transfer runs.
            TextButton(
                onClick = onChooseDifferentModel,
                enabled = !downloading && !testing && !importing,
                modifier = Modifier.fillMaxWidth().testTag("one_tap_choose_model")
            ) {
                Text("Choose or import a different model")
            }
            if (status.isNotBlank()) {
                Text(status, modifier = Modifier.padding(top = 20.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun oneTapMegabytes(bytes: Long): String =
    if (bytes < 0L) "—" else String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
