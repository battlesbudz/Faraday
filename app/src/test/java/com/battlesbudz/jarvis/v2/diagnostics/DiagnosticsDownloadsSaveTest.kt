package com.battlesbudz.jarvis.v2.diagnostics

import android.content.SharedPreferences
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.TimeZone
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * The save behind the "Save diagnostics" button: naming, content, and honest
 * reporting, driven through a fake destination. The MediaStore transport
 * itself is pinned by DiagnosticsDownloadsSaveMediaStoreTest.
 */
class DiagnosticsDownloadsSaveTest {
    private lateinit var defaultZone: TimeZone

    @Before fun pinZone() {
        defaultZone = TimeZone.getDefault()
        // The file-name stamp is device-local time; pin the zone so the
        // expected stamp below is exact on any machine.
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
    }

    @After fun restoreZone() { TimeZone.setDefault(defaultZone) }

    // 2026-11-02 01:15:00 in America/New_York.
    private val nowMs = 1_793_600_100_000L
    private val expectedName = "faraday-diagnostics-build1667-20261102-011500.json"

    @Test fun fileNameCarriesBuildNumberAndLocalTimestamp() {
        assertEquals(expectedName, DiagnosticsDownloadsSave.fileName("1667", nowMs))
    }

    @Test fun saveWritesToTheDestinationAndReportsTheFile() {
        val written = mutableListOf<Triple<String, String, ByteArray>>()
        val destination = DiagnosticsDownloadsSave.Destination { name, mime, payload ->
            written += Triple(name, mime, payload)
            name
        }
        val outcome = DiagnosticsDownloadsSave.save(destination, "1667", "{\"a\":1}", nowMs)
        assertEquals(DiagnosticsDownloadsSave.Outcome.Saved(expectedName), outcome)
        assertEquals("Saved $expectedName to Downloads", DiagnosticsDownloadsSave.message(outcome))
        assertEquals(1, written.size)
        assertEquals(expectedName, written.single().first)
        assertEquals("application/json", written.single().second)
        assertEquals("{\"a\":1}", written.single().third.toString(Charsets.UTF_8))
    }

    @Test fun savedContentIsTheSnapshotJsonExport() {
        val recorder = DiagnosticRecorder(preferences(), "build-save-test")
        recorder.startSession("Voice Call save-session")
        recorder.recordInferencePrompt("prompt-alpha")
        recorder.recordImportant("important-delta")
        recorder.record("audio-epsilon")
        val payload = DiagnosticsDownloadsSave.payload(recorder, "handoff-text", nowMs)

        var writtenName = ""
        var writtenBytes = ByteArray(0)
        val destination = DiagnosticsDownloadsSave.Destination { name, _, bytes ->
            writtenName = name
            writtenBytes = bytes
            name
        }
        val outcome = DiagnosticsDownloadsSave.save(destination, "1667", payload, nowMs)
        assertEquals(DiagnosticsDownloadsSave.Outcome.Saved(expectedName), outcome)
        assertEquals(expectedName, writtenName)
        assertEquals(payload, writtenBytes.toString(Charsets.UTF_8))

        // The envelope carries the recorder's snapshotJson unchanged (its own
        // generatedAtMs is stamped per call, so it is excluded from the diff).
        val envelope = JSONObject(payload)
        assertEquals("Jarvis OS V2 — latest Voice Call diagnostics", envelope.getString("title"))
        assertEquals(nowMs, envelope.getLong("generatedAtMs"))
        assertEquals("handoff-text", envelope.getString("microphoneHandoff"))
        val saved = envelope.getJSONObject("diagnostics")
        val direct = recorder.snapshotJson()
        assertEquals(direct.getString("build"), saved.getString("build"))
        assertEquals(direct.getString("session"), saved.getString("session"))
        for (section in listOf("inferencePrompts", "turnEvidence", "summaries", "callActionsAndTurns", "recentAudioEvents")) {
            val expected = direct.getJSONArray(section)
            val actual = saved.getJSONArray(section)
            assertEquals("section $section length", expected.length(), actual.length())
            for (i in 0 until expected.length()) assertEquals("section $section[$i]", expected.getString(i), actual.getString(i))
        }
    }

    @Test fun aRefusedDestinationIsAFailureNotASuccess() {
        val destination = DiagnosticsDownloadsSave.Destination { _, _, _ ->
            throw IOException("Downloads refused the new file")
        }
        val outcome = DiagnosticsDownloadsSave.save(destination, "1667", "{}", nowMs)
        assertEquals(DiagnosticsDownloadsSave.Outcome.Failed("Downloads refused the new file"), outcome)
        assertEquals("Save diagnostics failed: Downloads refused the new file", DiagnosticsDownloadsSave.message(outcome))
    }

    @Test fun aFailureWithoutAMessageStillReportsSomething() {
        val destination = DiagnosticsDownloadsSave.Destination { _, _, _ -> throw IllegalStateException() }
        val outcome = DiagnosticsDownloadsSave.save(destination, "1667", "{}", nowMs)
        assertTrue(outcome is DiagnosticsDownloadsSave.Outcome.Failed)
        val reason = (outcome as DiagnosticsDownloadsSave.Outcome.Failed).reason
        assertTrue(reason.isNotBlank())
        assertTrue(DiagnosticsDownloadsSave.message(outcome).startsWith("Save diagnostics failed: "))
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
