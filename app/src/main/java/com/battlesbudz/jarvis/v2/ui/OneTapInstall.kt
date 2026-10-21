package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
 */
@Composable
internal fun OneTapInstall(
    downloading: Boolean,
    testing: Boolean,
    importing: Boolean,
    downloadBytes: Long,
    downloadTotalBytes: Long,
    status: String,
    onInstall: () -> Unit,
    onChooseDifferentModel: () -> Unit
) {
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
