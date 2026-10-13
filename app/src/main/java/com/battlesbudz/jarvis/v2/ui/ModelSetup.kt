package com.battlesbudz.jarvis.v2.ui

import com.battlesbudz.jarvis.v2.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.items


@Composable
internal fun ModelSetup(
    modelSelector: @Composable (Boolean) -> Unit,
    ready: Boolean,
    gemmaReady: Boolean,
    downloadAvailable: Boolean,
    testing: Boolean,
    importing: Boolean,
    downloading: Boolean,
    downloadBytes: Long,
    downloadTotalBytes: Long,
    status: String,
    elapsedSeconds: Long,
    onDownload: () -> Unit,
    onPickGemma: () -> Unit,
    onTest: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Jarvis setup", style = MaterialTheme.typography.headlineMedium)
        modelSelector(!testing && !importing)
        Text(
            if (gemmaReady) {
                "The selected AI model is installed. Check it below to start chatting."
            } else {
                "Choose an AI model to use on your phone. Download it here or import its file. Voice prepares its speech models separately."
            },
            modifier = Modifier.padding(top = 12.dp, bottom = 20.dp)
        )
        Button(
            onClick = onDownload,
            modifier = Modifier.fillMaxWidth(),
            enabled = downloadAvailable && !gemmaReady && !testing && !importing && !downloading
        ) {
            Text(
                when {
                    downloading -> "Downloading and installing…"
                    !downloadAvailable -> "Use publisher download and import"
                    gemmaReady -> "Model installed"
                    else -> "Download selected model and voice setup"
                }
            )
        }
        if (downloading && downloadTotalBytes > 0L) {
            val progress = (downloadBytes.toFloat() / downloadTotalBytes.toFloat()).coerceIn(0f, 1f)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · ${formatMegabytes(downloadBytes)} / ${formatMegabytes(downloadTotalBytes)} MB " +
                    "(${(progress * 100).toInt()}%) · ${elapsedSeconds}s elapsed",
                modifier = Modifier.padding(top = 8.dp)
            )
        } else if (downloading) {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
            )
            Text(
                "${setupPhase(status)} · " +
                    (if (downloadBytes > 0L) "${formatMegabytes(downloadBytes)} MB processed · " else "") +
                    "${elapsedSeconds}s elapsed",
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        if (status.isNotBlank() && downloading) {
            Text(status, modifier = Modifier.padding(top = 20.dp))
        } else if (status.isNotBlank()) {
            Text(status, modifier = Modifier.padding(top = 20.dp))
        }
        OutlinedButton(
            onClick = onPickGemma,
            enabled = !testing && !importing && !downloading,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text("Import downloaded model")
        }
        Button(
            onClick = onTest,
            enabled = ready && !testing && !importing && !downloading,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp).testTag("model_check")
        ) {
            Text(if (testing) "Checking selected model…" else "Check selected model")
        }
    }
}

private fun setupPhase(status: String): String = when {
    status.contains("app storage", ignoreCase = true) -> "Step 1 of 5: checking app storage"
    status.contains("Downloads", ignoreCase = true) || status.contains("exact filename", ignoreCase = true) ->
        "Step 2 of 5: checking Downloads"
    status.contains("Importing", ignoreCase = true) -> "Step 3 of 5: importing the existing model"
    status.contains("Verifying", ignoreCase = true) -> "Step 4 of 5: verifying the model"
    status.contains("Assembling", ignoreCase = true) -> "Step 3 of 5: assembling the downloaded model"
    status.contains("Downloading", ignoreCase = true) -> "Downloading the model"
    status.contains("Loading ", ignoreCase = true) || status.contains("initializing", ignoreCase = true) ->
        "Step 5 of 5: initializing the selected model"
    status.contains("Downloading the local Jarvis voice model", ignoreCase = true) ->
        "Step 2 of 5: downloading Piper"
    status.contains("Installing Piper", ignoreCase = true) || status.contains("Piper unpack", ignoreCase = true) ->
        "Step 3 of 5: unpacking Piper"
    else -> "Preparing Jarvis"
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes < 0L) "—" else String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))
