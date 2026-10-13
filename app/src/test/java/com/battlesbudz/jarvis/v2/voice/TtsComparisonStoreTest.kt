package com.battlesbudz.jarvis.v2.voice

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class TtsComparisonStoreTest {
    @Test fun miroSelectionRetiresWithoutRelabelingItsMeasurements() {
        assertEquals(TtsEngine.PIPER_NORTHERN, TtsEngine.fromId("piper_miro_high"))
        assertEquals("Piper Miro High (British) (retired)", TtsEngine.diagnosticLabel("piper_miro_high"))
        assertFalse(TtsEngine.entries.any { it.id == "piper_miro_high" })
    }

    @Test fun retiredSelectionFallsBackButHistoricalIdentitySurvives() {
        val prefs = preferences()
        prefs.edit().putString("engine", "kokoro_int8")
            .putString("results", """[{"engine":"kokoro_int8","model":"kokoro-int8-en-v0_19","atMs":1}]""").apply()
        val restored = TtsComparisonStore(prefs)
        assertEquals(TtsEngine.PIPER_NORTHERN, restored.selectedEngine())
        assertFalse(TtsEngine.entries.any { it.id == "kokoro_int8" })
        assertTrue(restored.snapshot().contains("TTS Kokoro INT8 (retired)"))
        assertTrue(restored.snapshot().contains("model=kokoro-int8-en-v0_19"))
        for (id in listOf("piper", "piper_alan", "piper_ryan_high")) {
            prefs.edit().putString("engine", id).apply()
            assertEquals(TtsEngine.PIPER_NORTHERN, restored.selectedEngine())
            assertTrue(TtsEngine.diagnosticLabel(id).endsWith("(retired)"))
            assertFalse(TtsEngine.diagnosticLabel(id).startsWith("Kokoro"))
        }
        assertEquals(setOf(TtsEngine.PIPER_NORTHERN), TtsEngine.entries.toSet())
        assertEquals("future_voice", TtsEngine.diagnosticLabel("future_voice"))
    }

    @Test fun northernPiperSelectionSurvivesRestart() {
        val prefs = preferences()
        prefs.edit().putString("engine", TtsEngine.PIPER_NORTHERN.id).apply()
        assertEquals(TtsEngine.PIPER_NORTHERN, TtsComparisonStore(prefs).selectedEngine())
    }



    @Test fun callEvidenceRemainsBoundedAndDoesNotOverwriteArchivedBenchmarks() {
        val prefs = preferences()
        val archive = """[{"engine":"pocket_paul","atMs":1,"model":"historical"}]"""
        prefs.edit().putString("benchmark_results", archive)
            .putString("call_profile_piper_northern_english_male_medium", "threads-2-opening-40-speed-0.9").apply()
        val store = TtsComparisonStore(prefs)
        val metrics = TtsSessionMetrics(10, 20, 50, 80, 0, 1f, 0, 0, 1, 3, "hash", 4, true, null)
        repeat(45) { store.add(TtsEngine.PIPER_NORTHERN, "voice-call", "$it", metrics) }
        val restored = TtsComparisonStore(prefs)
        assertEquals(40, restored.records().count { it.optString("source") == "voice-call" })
        assertEquals(archive, prefs.getString("benchmark_results", null))
        assertEquals(1f, restored.records().last().getDouble("playback_speed").toFloat())
        assertEquals(4, restored.records().last().getInt("threads"))
    }

    private fun preferences(): SharedPreferences {
        val data = mutableMapOf<String, String?>()
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString" -> { data[args[0] as String] = args[1] as String?; editor }
                "remove" -> { data.remove(args[0] as String); editor }
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
