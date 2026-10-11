package com.battlesbudz.jarvis.v2.voice

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Preference migration cannot load models, delete old downloads or change Gemma input authority. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetiredAsrPreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
    @Before fun reset() { preferences.edit().clear().commit() }
    @After fun cleanup() { preferences.edit().clear().commit() }

    @Test fun retiredSelectionsMigrateToCpuWhisperAndStayStableWhenReopened() {
        preferences.edit().putString("engine", "moonshine_small")
            .putString("caption_engine", "moonshine_small")
            .putBoolean("gemma_whisper_captions", true).commit()
        repeat(3) {
            val reopened = ContextWrapper(context)
            assertEquals(AsrEngine.WHISPER, AsrEngine.selected(reopened))
            assertEquals(AsrEngine.WHISPER, VoiceInputMode.captionEngine(reopened))
            assertEquals(AsrEngine.WHISPER.id, preferences.getString("engine", null))
            assertEquals(AsrEngine.WHISPER.id, preferences.getString("caption_engine", null))
        }
    }

    @Test fun legacyOffWinsOverRetiredCaptionSelection() {
        preferences.edit().putString("caption_engine", "moonshine_small")
            .putBoolean("gemma_whisper_captions", false).commit()
        assertNull(VoiceInputMode.captionEngine(context))
        assertEquals("off", preferences.getString("caption_engine", null))
        assertFalse(preferences.getBoolean("gemma_whisper_captions", true))
    }

    @Test fun currentEngineChoicesAndExplicitOffAreNotRewritten() {
        for (engine in AsrEngine.entries) {
            AsrEngine.select(context, engine)
            VoiceInputMode.captionEngine(context, engine)
            val before = preferences.all.toMap()
            assertEquals(engine, AsrEngine.selected(context))
            assertEquals(engine, VoiceInputMode.captionEngine(context))
            assertEquals(before, preferences.all)
        }
        VoiceInputMode.captionEngine(context, null)
        val before = preferences.all.toMap()
        assertNull(VoiceInputMode.captionEngine(context))
        assertEquals(before, preferences.all)
    }

    @Test fun migrationPreservesGemmaInputModeDownloadedWeightsAndHistory() {
        preferences.edit().putString("engine", "moonshine_small")
            .putString("caption_engine", "moonshine_small")
            .putString("input_mode", "gemma_audio")
            .putBoolean("gemma_whisper_captions", true)
            .putString("turns", "retained Moonshine comparison history")
            .putLong("unrelated_counter", 19).commit()
        val before = preferences.all.toMap()
        val oldWeights = File.createTempFile("retired-moonshine-", ".bin", context.filesDir)
        val bytes = ByteArray(37) { it.toByte() }
        try {
            oldWeights.writeBytes(bytes)
            AsrEngine.selected(context)
            VoiceInputMode.captionEngine(context)
            assertEquals(before + mapOf("engine" to AsrEngine.WHISPER.id,
                "caption_engine" to AsrEngine.WHISPER.id), preferences.all)
            assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.selected(context))
            assertArrayEquals(bytes, oldWeights.readBytes())
        } finally { oldWeights.delete() }
    }

    @Test fun freshDefaultsRemainCpuWithoutCreatingSelectionPreferences() {
        assertEquals(AsrEngine.WHISPER, AsrEngine.selected(context))
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.captionEngine(context))
        assertFalse(preferences.contains("engine"))
        assertFalse(preferences.contains("caption_engine"))
    }
}
