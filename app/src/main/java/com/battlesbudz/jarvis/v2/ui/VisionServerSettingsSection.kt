package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.battlesbudz.jarvis.v2.vision.PhoneVisionClient
import com.battlesbudz.jarvis.v2.vision.VisionServerSettings
import kotlinx.coroutines.launch

/**
 * Settings section for the Phone Inference app's localhost vision server.
 * Lives in the Settings dialog in [VoiceCallScreen], next to the other
 * input settings. Turning the server on makes the next chat photo go to
 * the server (POST /detect with the saved X-Vision-Token) instead of the
 * built-in in-process models. Default off.
 */
@Composable
internal fun VisionServerSettingsSection(enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var useServer by remember { mutableStateOf(VisionServerSettings.enabled(context)) }
    var token by remember { mutableStateOf(VisionServerSettings.token(context)) }
    var message by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    Column {
        Text("Phone Inference server", style = MaterialTheme.typography.titleMedium)
        Text(
            "Send chat photos to the Phone Inference app on this phone instead of running vision " +
                "inside Faraday. Start the server in that app, then paste its X-Vision-Token here.",
            style = MaterialTheme.typography.bodySmall
        )
        Text("Vision source: ${if (useServer) "Phone Inference server" else "Built-in (in-process)"}")
        TextButton(
            enabled = enabled,
            modifier = Modifier.testTag("vision_server_toggle"),
            onClick = {
                val next = !useServer
                VisionServerSettings.setEnabled(context, next)
                useServer = next
                message = if (next) {
                    "Phone Inference server will handle the next photo you send."
                } else {
                    "Built-in vision will handle the next photo you send."
                }
            }
        ) { Text(if (useServer) "Use built-in vision" else "Use Phone Inference server") }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            enabled = enabled,
            label = { Text("X-Vision-Token") },
            modifier = Modifier.testTag("vision_server_token").fillMaxWidth()
        )
        TextButton(
            enabled = enabled,
            modifier = Modifier.testTag("vision_server_save_token"),
            onClick = {
                VisionServerSettings.setToken(context, token)
                message = "Token saved. It is used the next time a photo goes to the server."
            }
        ) { Text("Save token") }
        TextButton(
            enabled = enabled && !testing,
            modifier = Modifier.testTag("vision_server_test"),
            onClick = {
                // Persist the token field first: the health check needs no token,
                // so a passing test was misleading when the token was typed but
                // never saved — the next photo would then fail for a missing token.
                VisionServerSettings.setToken(context, token)
                if (token.isBlank()) {
                    message = "Token saved (empty). Paste the X-Vision-Token from the Phone " +
                        "Inference app — photos need it, the connection test does not."
                    return@TextButton
                }
                testing = true
                scope.launch {
                    try {
                        val health = PhoneVisionClient().health()
                        message = "Server reachable: ${health.model} (version ${health.version}). Token saved." +
                            if (useServer) " Photos will go to the server."
                            else " Turn on \"Use Phone Inference server\" to send photos there."
                    } catch (error: Throwable) {
                        message = error.message ?: "Server test failed."
                    } finally {
                        testing = false
                    }
                }
            }
        ) { Text(if (testing) "Testing…" else "Test connection") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
