package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AsrComparisonStoreTest {
    private val metrics = AsrCaptureMetrics(100, 400, 2000, 500, 150, 350, 4, 70, "trailing_silence")

    @Test fun retiredSelectionCannotChangeNewTurnsAndOldMeasurementsSurvive() {
        val preferences = preferences()
        preferences.edit().putString("engine", "zipformer").putString("turns",
            """[{"id":"old","engine":"zipformer","model":"old-model","transcript":"HELLO"}]""").apply()
        val store = AsrComparisonStore(preferences)
        store.add("new", metrics, "hello")
        assertEquals("zipformer", store.records().first().getString("engine"))
        assertEquals(AsrEngine.WHISPER.id, store.records().last().getString("engine"))
        assertTrue(store.snapshot().contains("Zipformer (retired)"))
    }

    @Test fun retiredMoonshineHistoryRemainsReadableBesideNewWhisperTurns() {
        val preferences = preferences()
        preferences.edit().putString("turns",
            """[{"id":"old","engine":"moonshine_small","model":"old-model","transcript":"retained"}]""").apply()
        val store = AsrComparisonStore(preferences)
        store.add("new", metrics, "current")
        assertEquals("moonshine_small", store.records().first().getString("engine"))
        assertEquals("retained", store.records().first().getString("transcript"))
        assertEquals(AsrEngine.WHISPER.id, store.records().last().getString("engine"))
        assertTrue(store.snapshot().contains("Moonshine Small Streaming (retired)"))
    }

    @Test fun keepsEngineReferenceAndTimingAcrossStoreRecreation() {
        val preferences = preferences()
        val store = AsrComparisonStore(preferences)
        store.add("one", metrics, "hello ants")
        store.setReference("one", "hello astronauts")
        store.update("one", "final_to_first_text_ms", 800L)
        val restored = AsrComparisonStore(preferences)
        assertTrue(restored.snapshot().contains("word_error_rate_percent=50.0"))
        assertTrue(restored.snapshot().contains("final_to_first_text_ms=800"))
    }

    @Test fun retentionIsBoundedAndLateUpdatesDoNotRestoreEvictedTurns() {
        val store = AsrComparisonStore(preferences())
        repeat(23) { store.add(it.toString(), metrics, "hello") }
        assertEquals(20, store.records().size)
        assertEquals("3", store.records().first().getString("id"))
        assertTrue(store.records().all { it.getString("engine") == AsrEngine.WHISPER.id })
        store.update("0", "late_update", 1)
        assertEquals(20, store.records().size)
        assertTrue(store.snapshot().contains("unscored"))
    }

    @Test fun missingLivePartialStaysUnavailable() {
        val store = AsrComparisonStore(preferences())
        store.add("one", metrics.copy(firstPartialAfterSpeechMs = null), "hello")
        assertTrue(store.snapshot().contains("speech_detected_to_first_partial_ms=unavailable"))
        store.setReference("one", "hello")
        store.setReference("one", "")
        assertTrue(store.snapshot().contains("unscored"))
    }

    private fun preferences(): SharedPreferences {
        val data = mutableMapOf<String, String?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { data[args[0] as String] = args[1] as String?; editor }
                "apply" -> null
                else -> error("Unexpected preferences edit ${method.name}")
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString" -> data[args[0]] ?: args[1]
                "edit" -> editor
                else -> error("Unexpected preferences read ${method.name}")
            }
        } as SharedPreferences
    }
}
