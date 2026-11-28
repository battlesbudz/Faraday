package com.battlesbudz.jarvis.v2.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.battlesbudz.jarvis.v2.privilege.PairingNotifier
import com.battlesbudz.jarvis.v2.privilege.PairingSetupController
import com.battlesbudz.jarvis.v2.privilege.PairingWizardFlow
import com.battlesbudz.jarvis.v2.privilege.PairingWizardSystemState
import kotlinx.coroutines.delay

/** User-facing pairing and revocation controls for the A1 helper. */
@Composable
internal fun AppControlPairingSection(enabled: Boolean, controller: PairingSetupController? = null) {
    val context = LocalContext.current
    val pairing = controller ?: remember { PairingSetupController.get(context) }
    val state by pairing.state.collectAsState()
    var status by remember(pairing) { mutableStateOf(pairing.status()) }
    var system by remember(context) { mutableStateOf(PairingWizardSystemState.read(context)) }
    var code by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var manualExpanded by remember { mutableStateOf(false) }
    var repairMode by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(emptySet<PairingWizardFlow.Step>()) }
    var revisit by remember { mutableStateOf<PairingWizardFlow.Step?>(null) }
    var fallbackStep by remember { mutableStateOf<PairingWizardFlow.Step?>(null) }
    var fallbackConfirmation by remember { mutableStateOf("I've turned it on, next") }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
    }

    LaunchedEffect(context, pairing, state) {
        while (true) {
            status = pairing.status()
            system = PairingWizardSystemState.read(context)
            permissionGranted = if (Build.VERSION.SDK_INT >= 33) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
            delay(1_000)
        }
    }
    LaunchedEffect(state.lastMessage) {
        if (repairMode && state.lastMessage?.startsWith("Paired.") == true) repairMode = false
    }
    LaunchedEffect(revisit, confirmed, system, state.dialogFound, status.present) {
        val step = revisit ?: return@LaunchedEffect
        val readingIsOn = when (step) {
            PairingWizardFlow.Step.DeveloperOptions -> system.developerOptions != false
            PairingWizardFlow.Step.UsbDebugging -> system.usbDebugging != false
            PairingWizardFlow.Step.WirelessDebugging -> system.wirelessDebugging != false
            PairingWizardFlow.Step.PairingCode -> state.dialogFound
            PairingWizardFlow.Step.Connect -> status.present
        }
        if (step in confirmed || readingIsOn) revisit = null
    }

    val helperConnected = status.present
    if (helperConnected && !repairMode) {
        Text("Phone control is set up", style = MaterialTheme.typography.titleMedium)
        Text("Helper status: connected.", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("app_control_pairing_status"))
        TextButton(enabled = enabled, onClick = {
            repairMode = true
            manualExpanded = false
            confirmed = emptySet()
        }) { Text("Pair again") }
        TextButton(enabled = enabled, modifier = Modifier.testTag("app_control_pairing_revoke"), onClick = pairing::revoke) {
            Text("Revoke pairing")
        }
        return
    }

    val paired = !repairMode && (helperConnected ||
        status.reason?.contains("consented wireless-debugging recovery did not establish") == true ||
        state.lastMessage?.startsWith("Paired.") == true)
    val lastFailure = state.lastMessage?.takeIf {
        !state.attemptInFlight && !it.startsWith("Paired.") &&
            !it.startsWith("Pairing revoked.")
    }
    val input = PairingWizardFlow.Input(
        developerOptions = system.developerOptions,
        usbDebugging = system.usbDebugging,
        wirelessDebugging = system.wirelessDebugging,
        wifiConnected = system.wifiConnected,
        accessibilityRunning = system.accessibilityRunning,
        notificationsAllowed = system.notificationsAllowed,
        pairingChannelBlocked = system.pairingChannelBlocked,
        helperPresent = false,
        paired = paired,
        armed = state.armed,
        attemptInFlight = state.attemptInFlight,
        dialogFound = state.dialogFound,
        lastFailure = if (revisit == null) lastFailure else null,
        confirmed = confirmed,
    )
    var view = PairingWizardFlow.evaluate(input)
    if (revisit != null) {
        val step = revisit!!
        view = view.copy(
            phase = if (state.armed || state.attemptInFlight) PairingWizardFlow.Phase.Working else PairingWizardFlow.Phase.Guide,
            currentStep = step,
            marks = view.marks + (step to PairingWizardFlow.Mark.Current),
            suspect = null,
        )
    }
    fun openSettings(
        intent: Intent,
        pairBeforeOpen: Boolean = false,
        fallbackText: String = "I've turned it on, next",
    ) {
        val step = view.currentStep
        if (pairBeforeOpen) pairing.arm()
        val resolvable = runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)
        if (!resolvable) {
            fallbackStep = step
            fallbackConfirmation = fallbackText
            return
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                if (pairBeforeOpen) pairing.disarm()
                fallbackStep = step
                fallbackConfirmation = fallbackText
            }
    }

    fun notificationAction() {
        when {
            Build.VERSION.SDK_INT >= 33 && !permissionGranted -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            system.pairingChannelBlocked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, PairingNotifier.CHANNEL_ID)
                }, fallbackText = "I've turned on notifications, next")
            }
            else -> openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }, fallbackText = "I've turned on notifications, next")
        }
    }

    Column {
        Text("App control pairing", style = MaterialTheme.typography.titleMedium)
        Text(
            helperStatusText(status.present, status.reason),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("app_control_pairing_status"),
        )
        if (view.phase == PairingWizardFlow.Phase.Failed && revisit == null) {
            Text(pairingFailureText(lastFailure.orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        PairingWizardFlow.steps.forEachIndexed { index, step ->
            val mark = view.marks[step] ?: PairingWizardFlow.Mark.Todo
            val title = stepTitle(step)
            when (mark) {
                PairingWizardFlow.Mark.Done -> Text("${index + 1}. $title · ${if (step in switchSteps) "On" else "Done"}", style = MaterialTheme.typography.bodyMedium)
                PairingWizardFlow.Mark.Skipped -> Text("${index + 1}. $title", style = MaterialTheme.typography.bodyMedium)
                PairingWizardFlow.Mark.Failed -> Text("${index + 1}. $title · Needs a check", style = MaterialTheme.typography.bodyMedium)
                PairingWizardFlow.Mark.Current -> {
                    Text("${index + 1}. $title", style = MaterialTheme.typography.titleSmall)
                    when (step) {
                        PairingWizardFlow.Step.DeveloperOptions -> {
                            Text("Tap Build number 7 times.", style = MaterialTheme.typography.bodySmall)
                            if (fallbackStep == step) {
                                Button(enabled = enabled, onClick = { confirmed = confirmed + step; fallbackStep = null; revisit = null }) {
                                    Text("I've turned it on, next")
                                }
                            } else {
                                Button(enabled = enabled, onClick = { openSettings(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }) { Text("Open Settings") }
                            }
                            if (fallbackStep != step) confirmButton(enabled) { confirmed = confirmed + step; revisit = null }
                        }
                        PairingWizardFlow.Step.UsbDebugging -> {
                            Text("Turn on USB debugging, then come back.", style = MaterialTheme.typography.bodySmall)
                            if (fallbackStep == step) {
                                Button(enabled = enabled, onClick = { confirmed = confirmed + step; fallbackStep = null; revisit = null }) {
                                    Text("I've turned it on, next")
                                }
                            } else {
                                Button(enabled = enabled, onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Open Settings") }
                            }
                            if (fallbackStep != step) confirmButton(enabled) { confirmed = confirmed + step; revisit = null }
                        }
                        PairingWizardFlow.Step.WirelessDebugging -> {
                            if (view.wifiMissing) {
                                Text("Wireless debugging needs Wi-Fi. Join any Wi-Fi network first (it doesn't need internet).", style = MaterialTheme.typography.bodySmall)
                                if (fallbackStep == step) {
                                    Button(enabled = enabled, onClick = { confirmed = confirmed + step; fallbackStep = null; revisit = null }) {
                                        Text("I've joined Wi-Fi, next")
                                    }
                                } else {
                                    Button(enabled = enabled, onClick = { openSettings(Intent(Settings.ACTION_WIFI_SETTINGS)) }) { Text("Open Wi-Fi settings") }
                                }
                            } else {
                                Text("Turn on Wireless debugging. Tick 'Always allow on this network', then tap Allow.", style = MaterialTheme.typography.bodySmall)
                                if (fallbackStep == step) {
                                    Button(enabled = enabled, onClick = { confirmed = confirmed + step; fallbackStep = null; revisit = null }) {
                                        Text("I've turned it on, next")
                                    }
                                } else {
                                    Button(enabled = enabled, onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Open Settings") }
                                }
                            }
                            if (fallbackStep != step) confirmButton(enabled) { confirmed = confirmed + step; revisit = null }
                        }
                        PairingWizardFlow.Step.PairingCode -> {
                            Text(
                                if (view.autoRead) {
                                    "When the code appears, wait a few seconds — Faraday reads it and finishes pairing by itself. Tap the words 'Wireless debugging', then 'Pair device with pairing code', and leave that dialog open."
                                } else {
                                    "Tap the words 'Wireless debugging', then 'Pair device with pairing code', and leave that dialog open. Faraday's notification will pop up over it — type the 6 digits there. Don't leave the dialog: the code stops working if it closes."
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (state.armed) {
                                Text("Waiting for the pairing dialog…", style = MaterialTheme.typography.bodySmall)
                                Button(enabled = enabled, modifier = Modifier.testTag("app_control_pairing_stop"), onClick = pairing::disarm) {
                                    Text("Stop")
                                }
                            } else if (fallbackStep == step) {
                                Button(enabled = enabled, modifier = Modifier.testTag("app_control_pairing_start"), onClick = { fallbackStep = null }) {
                                    Text(fallbackConfirmation)
                                }
                            } else if (view.needsNotifications) {
                                Button(enabled = enabled, onClick = ::notificationAction) { Text("Turn on notifications") }
                            } else {
                                Button(enabled = enabled, modifier = Modifier.testTag("app_control_pairing_start"), onClick = {
                                    openSettings(
                                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                                        pairBeforeOpen = true,
                                        fallbackText = "I've opened Wireless debugging, next",
                                    )
                                }) { Text("Open Wireless debugging") }
                            }
                            TextButton(
                                enabled = enabled,
                                modifier = Modifier.testTag("app_control_pairing_manual_toggle"),
                                onClick = { manualExpanded = !manualExpanded },
                            ) { Text(if (manualExpanded) "Hide code entry" else "No notification? Enter it here") }
                            if (manualExpanded) {
                                Text("Going back to Faraday closes the pairing dialog — use split screen, with the dialog on one side and Faraday on the other.", style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(
                                    value = code,
                                    onValueChange = { raw -> code = raw.filter(Char::isDigit).take(6) },
                                    enabled = enabled,
                                    label = { Text("Pairing code") },
                                    modifier = Modifier.fillMaxWidth().testTag("app_control_pairing_code"),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                )
                                if (!state.dialogFound) {
                                    OutlinedTextField(
                                        value = port,
                                        onValueChange = { raw -> port = raw.filter(Char::isDigit).take(5) },
                                        enabled = enabled,
                                        label = { Text("Port (optional)") },
                                        modifier = Modifier.fillMaxWidth().testTag("app_control_pairing_port"),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                    )
                                }
                                Button(
                                    enabled = enabled,
                                    modifier = Modifier.testTag("app_control_pairing_manual"),
                                    onClick = { pairing.submitManualCode(code, if (!state.dialogFound) port.toIntOrNull() else null) },
                                ) { Text("Pair with this code") }
                            }
                        }
                        PairingWizardFlow.Step.Connect -> Text("Connecting…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                PairingWizardFlow.Mark.Todo -> Text("${index + 1}. $title", style = MaterialTheme.typography.bodyMedium)
            }
        }

        if (view.phase == PairingWizardFlow.Phase.Failed && revisit == null && view.suspect != null) {
            val suspect = view.suspect
            TextButton(enabled = enabled, onClick = {
                confirmed = confirmed - suspect
                revisit = suspect
            }) {
                Text("Back to '${stepTitle(suspect)}'")
            }
        }

        if (state.armed && view.marks[PairingWizardFlow.Step.PairingCode] != PairingWizardFlow.Mark.Current) {
            Text("Waiting for the pairing dialog…", style = MaterialTheme.typography.bodySmall)
            Button(enabled = enabled, modifier = Modifier.testTag("app_control_pairing_stop"), onClick = pairing::disarm) {
                Text("Stop")
            }
        }

        if (view.currentStep == PairingWizardFlow.Step.PairingCode && view.phase == PairingWizardFlow.Phase.Working && !state.armed && state.attemptInFlight) {
            Text("Pairing…", style = MaterialTheme.typography.bodySmall)
        }

        TextButton(enabled = enabled, modifier = Modifier.testTag("app_control_pairing_revoke"), onClick = pairing::revoke) {
            Text("Revoke pairing")
        }
        Text(
            "Revoking stops app control until you pair again. Tasks stop instead of using your main screen.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

private val switchSteps = setOf(
    PairingWizardFlow.Step.DeveloperOptions,
    PairingWizardFlow.Step.UsbDebugging,
    PairingWizardFlow.Step.WirelessDebugging,
)

@Composable
private fun confirmButton(enabled: Boolean, onClick: () -> Unit) {
    TextButton(enabled = enabled, onClick = onClick) { Text("I've turned it on, next") }
}

private fun stepTitle(step: PairingWizardFlow.Step): String = when (step) {
    PairingWizardFlow.Step.DeveloperOptions -> "Developer options"
    PairingWizardFlow.Step.UsbDebugging -> "USB debugging"
    PairingWizardFlow.Step.WirelessDebugging -> "Wireless debugging"
    PairingWizardFlow.Step.PairingCode -> "Pairing code"
    PairingWizardFlow.Step.Connect -> "Connect"
}

private fun helperStatusText(present: Boolean, reason: String?): String = when {
    present -> "Helper status: connected."
    reason?.contains("did not establish") == true -> "Helper status: not connected — Faraday couldn't start the helper."
    reason?.contains("pairing is absent or revoked") == true -> "Helper status: not connected — pairing was revoked. Pair again to turn app control back on."
    reason?.contains("requires user consent") == true -> "Helper status: not connected — Faraday needs your permission to reconnect."
    else -> "Helper status: not connected — Faraday couldn't connect."
}

private fun pairingFailureText(reason: String): String {
    val text = reason.lowercase()
    return when {
        "code" in text && listOf("wrong", "expired", "reject").any { text.contains(it) } ->
            "That code did not work. Open a new pairing dialog and try again."
        listOf("endpoint", "unreachable", "connect", "wireless-debugging", "wireless debugging", "pairing transport")
            .any { text.contains(it) } ->
            "Faraday couldn't reach Wireless debugging. Check that it is on and that Wi-Fi is connected."
        reason.startsWith("Faraday hasn't found the pairing dialog yet") -> reason
        reason.startsWith("That is not the 6-digit code") ->
            "Enter the 6-digit code shown in the pairing dialog."
        else -> "Pairing did not finish. Check the current step and try again."
    }
}
