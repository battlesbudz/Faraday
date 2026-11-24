package com.battlesbudz.jarvis.v2.privilege

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.battlesbudz.jarvis.v2.actions.ScreenControlService
import kotlinx.coroutines.delay

/** Reads only Settings pairing windows while the setup controller has an active armed window. */
internal object PairingCodeReader {
    private const val MAX_NODES = 400

    suspend fun run(
        pollIntervalMs: Long,
        isArmed: () -> Boolean,
        attemptInFlight: () -> Boolean,
        discovery: () -> AdbEndpoint?,
        waitingToPair: () -> Boolean,
        windowTexts: () -> List<List<String>>,
        onAttempt: suspend (String, AdbEndpoint?) -> Unit,
    ) {
        val tried = mutableSetOf<String>()
        while (isArmed()) {
            if (!attemptInFlight()) {
                val endpoint = runCatching(discovery).getOrNull()
                val waiting = runCatching(waitingToPair).getOrDefault(false)
                for (texts in runCatching(windowTexts).getOrDefault(emptyList())) {
                    val reading = PairingScreen.read(texts)
                    val attempt = PairingScreen.attempt(
                        reading = reading,
                        pairingPortKnown = endpoint != null,
                        waitingToPair = isArmed() && !attemptInFlight() && waiting,
                        tried = tried,
                    ) ?: continue
                    tried += attempt.code
                    val override = endpoint?.let { found ->
                        attempt.port?.let { found.copy(port = it) } ?: found
                    }
                    onAttempt(attempt.code, override)
                    break
                }
            }
            delay(pollIntervalMs)
        }
    }

    fun settingsPackage(context: Context): String = runCatching {
        context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    }.getOrNull() ?: "com.android.settings"

    fun windowTexts(context: Context): List<List<String>> {
        val service = ScreenControlService.pairingReaderInstance() ?: return emptyList()
        val settingsPackage = settingsPackage(context)
        return runCatching { service.windows }.getOrNull().orEmpty().mapNotNull { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@mapNotNull null
            try {
                if (root.packageName?.toString() != settingsPackage) null else texts(root)
            } finally {
                root.recycle()
            }
        }
    }

    private fun texts(root: AccessibilityNodeInfo): List<String> {
        val result = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                try {
                    node.text?.toString()?.takeIf(String::isNotBlank)?.let(result::add)
                    for (index in 0 until node.childCount) {
                        node.getChild(index)?.let(queue::addLast)
                    }
                } finally {
                    if (node !== root) node.recycle()
                }
            }
        } finally {
            while (queue.isNotEmpty()) queue.removeFirst().recycle()
        }
        return result
    }
}
