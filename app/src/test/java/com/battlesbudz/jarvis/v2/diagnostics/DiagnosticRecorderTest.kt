package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class DiagnosticRecorderTest {
    @Test fun exactPromptSurvivesEventChatterAndProcessRestoreAndClearsAtNewCall() {
        val prefs = preferences()
        val recorder = DiagnosticRecorder(prefs, "test")
        recorder.startSession("call-one")
        val prompt = "model=e4b historySource=current_call\n" + "full prompt ".repeat(500) + "FINAL CURRENT QUESTION"
        recorder.recordInferencePrompt(prompt)
        repeat(150) { recorder.record("audio $it") }
        assertTrue(recorder.snapshot().contains(prompt))
        val restored = DiagnosticRecorder(prefs, "test")
        restored.restore()
        assertTrue(restored.snapshot().contains(prompt))
        restored.startSession("call-two")
        assertFalse(restored.snapshot().contains(prompt))
    }
    @Test fun promptRetentionIsBoundedSeparatelyFromTimingEvents() {
        val recorder = DiagnosticRecorder(preferences())
        repeat(30) { recorder.recordInferencePrompt("prompt-number-$it-END") }
        assertFalse(recorder.snapshot().contains("prompt-number-5-END"))
        assertTrue(recorder.snapshot().contains("prompt-number-6-END"))
        assertTrue(recorder.snapshot().contains("prompt-number-29-END"))
    }
    private fun preferences(): SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { values[args!![0] as String] = args[1]; editor }
                "remove" -> { values.remove(args!![0] as String); editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getString" -> values[args!![0]] ?: args[1]
                else -> null
            }
        } as SharedPreferences
    }
}
