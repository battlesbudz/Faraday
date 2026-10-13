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

/** Real Android preference integration; no recognizers, model downloads or microphone access. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechCaptureProfilePreferencesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)

    @Before fun reset() { preferences.edit().clear().commit() }
    @After fun cleanup() { preferences.edit().clear().commit() }

    private fun assertNormalized() {
        assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, SpeechCaptureProfile.selected(context))
        assertEquals("communication_noise_filtered", preferences.getString("capture_profile", null))
    }

    @Test fun freshPreferencesAreNormalized() = assertNormalized()
    @Test fun legacySpeechClarityIsNormalized() {
        preferences.edit().putString("capture_profile", "speech_preserving").commit()
        assertNormalized()
    }
    @Test fun existingCallProcessingIsPreserved() {
        preferences.edit().putString("capture_profile", "communication_noise_filtered").commit()
        assertNormalized()
    }
    @Test fun unknownAndEmptyIdsAreNormalized() {
        for (id in listOf("", "unknown_profile")) {
            preferences.edit().putString("capture_profile", id).commit()
            assertNormalized()
        }
    }
    @Test fun wrongStoredTypeIsNormalizedInsteadOfCrashing() {
        preferences.edit().putBoolean("capture_profile", true).commit()
        assertNormalized()
    }
    @Test fun repeatedReadsAndNewContextWrappersAreIdempotent() {
        preferences.edit().putString("capture_profile", "speech_preserving").commit()
        assertNormalized()
        val normalized = preferences.all.toMap()
        repeat(5) {
            val reopenedContext = ContextWrapper(context)
            assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, SpeechCaptureProfile.selected(reopenedContext))
            assertEquals(normalized, preferences.all)
        }
    }
    @Test fun normalizationOnlyChangesTheProfileKeyAndPreservesOtherPreferencesAndFiles() {
        preferences.edit().putString("capture_profile", "speech_preserving")
            .putString("input_mode", "gemma_audio")
            .putString("caption_engine", "moonshine")
            .putBoolean("gemma_whisper_captions", false)
            .putLong("unrelated_counter", 17L).commit()
        val before = preferences.all.toMap()
        val otherStores = listOf("conversations", "model_setup").associateWith { name ->
            val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            store.edit().putString("profile_migration_fixture", "retained $name data").commit()
            store.all.toMap()
        }
        val history = File.createTempFile("profile-history-", ".json", context.filesDir)
        val model = File.createTempFile("profile-model-", ".bin", context.filesDir)
        val bytes = ByteArray(33) { it.toByte() }
        try {
            history.writeText("retained conversation fixture")
            model.writeBytes(bytes)
            repeat(3) { assertNormalized() }
            assertEquals(before + ("capture_profile" to "communication_noise_filtered"), preferences.all)
            otherStores.forEach { (name, original) ->
                assertEquals(original, context.getSharedPreferences(name, Context.MODE_PRIVATE).all)
            }
            assertEquals("retained conversation fixture", history.readText())
            assertArrayEquals(bytes, model.readBytes())
        } finally {
            otherStores.keys.forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    .remove("profile_migration_fixture").commit()
            }
            history.delete()
            model.delete()
        }
    }
}
