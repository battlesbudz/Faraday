package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import org.json.JSONArray

class DiagnosticRecorder(
    private val preferences: SharedPreferences,
    private val buildLabel: String = "unknown"
) {
    private val inferencePrompts = ArrayDeque<String>()
    private val entries = mutableListOf<String>()
    private val important = mutableListOf<String>()
    private val summaries = mutableListOf<String>()
    private val turnEvidence = linkedMapOf<String, String>()

    /** Separate retention: timing chatter cannot evict or truncate the model input. */
    fun recordInferencePrompt(entry: String) = synchronized(entries) {
        inferencePrompts.addLast("atMs=${System.currentTimeMillis()}\n$entry")
        while (inferencePrompts.size > 24) inferencePrompts.removeFirst()
        preferences.edit().putString("diagnostics_inference_prompts",
            JSONArray().also { array -> inferencePrompts.forEach(array::put) }.toString()).apply()
    }

    fun recordTurnEvidence(turn: String, category: String, entry: String) = synchronized(entries) {
        turnEvidence["$turn/$category"] = "turn=$turn $category atMs=${System.currentTimeMillis()}\n${entry.take(1500)}"
        while (turnEvidence.keys.map { it.substringBefore('/') }.distinct().size > 12) {
            val oldest = turnEvidence.keys.first().substringBefore('/')
            turnEvidence.keys.removeAll { it.substringBefore('/') == oldest }
        }
        val saved = org.json.JSONObject().also { obj -> turnEvidence.forEach { (key, value) -> obj.put(key, value) } }
        preferences.edit().putString("diagnostics_turn_evidence", saved.toString()).apply()
    }
    private var sessionLabel = "Previous app runtime (may include earlier calls or chat)"

    fun startSession(label: String) {
        synchronized(entries) {
            sessionLabel = "$label recordedByBuild=$buildLabel startedAtMs=${System.currentTimeMillis()}"
            inferencePrompts.clear()
            entries.clear()
            important.clear()
            summaries.clear()
            turnEvidence.clear()
            preferences.edit().remove("diagnostics_inference_prompts").remove("diagnostics_turn_evidence").remove("diagnostics_summaries").remove("diagnostics_important").putString("diagnostics_session", sessionLabel)
                .putString("diagnostics", "[]").apply()
        }
    }

    fun restore(): List<String> {
        val stored = preferences.getString("diagnostics", null).orEmpty()
        val restored = if (stored.isBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(stored)
                (0 until array.length())
                    .map { array.getString(it) }
                    .filter { it.isNotBlank() }
                    .takeLast(100)
            }.getOrElse {
                stored.split("\n\n").filter { it.isNotBlank() }.takeLast(100)
            }
        }
        synchronized(entries) {
            inferencePrompts.clear()
            runCatching {
                val saved = JSONArray(preferences.getString("diagnostics_inference_prompts", "[]"))
                ((saved.length() - 24).coerceAtLeast(0) until saved.length()).forEach { inferencePrompts.addLast(saved.getString(it)) }
            }
            sessionLabel = preferences.getString("diagnostics_session", null) ?: sessionLabel
            entries.clear()
            entries.addAll(restored)
            important.clear()
            runCatching {
                val saved = JSONArray(preferences.getString("diagnostics_important", "[]"))
                (0 until saved.length()).map { saved.getString(it).take(1200) }.takeLast(64)
            }.getOrDefault(emptyList()).forEach(important::add)
        }
        synchronized(entries) {
            summaries.clear()
            runCatching {
                val saved = JSONArray(preferences.getString("diagnostics_summaries", "[]"))
                (0 until saved.length()).map { saved.getString(it).take(1200) }.takeLast(48)
            }.getOrDefault(emptyList()).forEach(summaries::add)
        }
        synchronized(entries) {
            turnEvidence.clear()
            runCatching {
                val saved = org.json.JSONObject(preferences.getString("diagnostics_turn_evidence", "{}"))
                saved.keys().asSequence().toList().takeLast(60).forEach { key -> turnEvidence[key] = saved.getString(key).take(1700) }
            }
        }
        return restored
    }

    fun snapshot(): String {
        return synchronized(entries) {
            "Running build: $buildLabel\n$sessionLabel\n\nExact Gemma prompt submissions (latest 24; includes drafts and retries):\n${inferencePrompts.joinToString("\n\n")}\n\nRetained turn evidence (up to 12 turns):\n${turnEvidence.values.joinToString("\n\n")}\n\nTiming and recognition summaries:\n${summaries.joinToString("\n\n")}\n\nCall actions and turns:\n${important.joinToString("\n\n")}\n\nRecent audio events:\n" + entries.takeLast(100).joinToString("\n\n")
                .ifBlank { "No runtime events in this session yet." }
        }
    }

    fun recordPreviousProcessExit(context: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        runCatching {
            val manager = context.getSystemService(android.app.ActivityManager::class.java) ?: return
            val exit = manager.getHistoricalProcessExitReasons(context.packageName, 0, 5)
                .firstOrNull { it.pid != android.os.Process.myPid() } ?: return
            if (exit.timestamp <= preferences.getLong("previous_process_exit_at", 0)) return
            val reason = when (exit.reason) {
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                android.app.ApplicationExitInfo.REASON_CRASH -> "managed_crash"
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                android.app.ApplicationExitInfo.REASON_ANR -> "not_responding"
                else -> "reason_${exit.reason}"
            }
            preferences.edit().putLong("previous_process_exit_at", exit.timestamp)
                .putString("previous_process_exit", "Previous Android process exit (not the current call): " +
                    "atMs=${exit.timestamp} reason=$reason status=${exit.status} " +
                    "pssKb=${exit.pss} rssKb=${exit.rss} description=${exit.description?.take(1000)}")
                .apply()
        }
    }

    fun recordSummary(entry: String) {
        synchronized(entries) {
            summaries.add("atMs=${System.currentTimeMillis()}\n${entry.take(1200)}")
            while (summaries.size > 48) summaries.removeAt(0)
            val saved = JSONArray().also { array -> summaries.forEach(array::put) }
            preferences.edit().putString("diagnostics_summaries", saved.toString()).apply()
        }
    }

    fun recordImportant(entry: String) {
        synchronized(entries) {
            important.add("atMs=${System.currentTimeMillis()}\n${entry.take(1200)}")
            while (important.size > 64) important.removeAt(0)
            val saved = JSONArray().also { array -> important.forEach(array::put) }
            preferences.edit().putString("diagnostics_important", saved.toString()).apply()
        }
    }

    fun record(entry: String) {
        synchronized(entries) {
            entries.add("atMs=${System.currentTimeMillis()}\n${entry.take(1_200)}")
            while (entries.size > 100) entries.removeAt(0)
            val persisted = JSONArray().also { array ->
                entries.takeLast(100).forEach(array::put)
            }.toString()
            preferences.edit().putString("diagnostics", persisted).apply()
        }
    }
}
