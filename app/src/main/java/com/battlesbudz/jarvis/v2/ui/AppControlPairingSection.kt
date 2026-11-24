package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.privilege.PairingSetupController

/** User-facing pairing and revocation controls for the A1 helper. */
@Composable
internal fun AppControlPairingSection(enabled: Boolean, controller: PairingSetupController? = null) {
    val context = LocalContext.current
    val pairing = controller ?: remember { PairingSetupController.get(context) }
    val state by pairing.state.collectAsState()
    val status = remember(pairing, state) { pairing.status() }
    var code by remember { mutableStateOf("") }

    Column {
        Text("App control pairing", style = MaterialTheme.typography.titleMedium)
        Text(
            "App control runs a small helper with shell permissions. Faraday pairs with this phone's own wireless debugging to start it — no computer and no root.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (status.present) "Helper status: connected."
            else "Helper status: not connected — ${status.reason ?: "helper unavailable"}.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("app_control_pairing_status"),
        )
        Text("To pair:", style = MaterialTheme.typography.bodyMedium)
        Text("1. In Android Settings, open Developer options → Wireless debugging and turn Wireless debugging on.", style = MaterialTheme.typography.bodySmall)
        Text("2. Tap “Pair device with pairing code” and leave the dialog open — it shows a 6-digit code.", style = MaterialTheme.typography.bodySmall)
        Text("3. Tap “Start pairing” here. If Faraday's accessibility service is on, Faraday reads the code from that dialog and pairs by itself. If not, a notification asks you to type the code — or type it below.", style = MaterialTheme.typography.bodySmall)

        if (state.armed) {
            Text("Waiting for the pairing dialog…", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = enabled,
                modifier = Modifier.testTag("app_control_pairing_stop"),
                onClick = pairing::disarm,
            ) { Text("Stop") }
        } else {
            Button(
                enabled = enabled,
                modifier = Modifier.testTag("app_control_pairing_start"),
                onClick = pairing::arm,
            ) { Text("Start pairing") }
        }

        OutlinedTextField(
            value = code,
            onValueChange = { raw -> code = raw.filter(Char::isDigit).take(6) },
            enabled = enabled,
            label = { Text("Pairing code") },
            modifier = Modifier.fillMaxWidth().testTag("app_control_pairing_code"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
        )
        Button(
            enabled = enabled,
            modifier = Modifier.testTag("app_control_pairing_manual"),
            onClick = { pairing.submitManual(code) },
        ) { Text("Pair with this code") }
        state.lastMessage?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        TextButton(
            enabled = enabled,
            modifier = Modifier.testTag("app_control_pairing_revoke"),
            onClick = pairing::revoke,
        ) { Text("Revoke pairing") }
        Text(
            "Revoking stops app control until you pair again. Tasks stop instead of using your main screen.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
