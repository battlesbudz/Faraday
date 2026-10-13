package com.battlesbudz.jarvis.v2.ui

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.battlesbudz.jarvis.v2.voice.AsrEngine
import com.battlesbudz.jarvis.v2.voice.SpeechCaptureProfile
import com.battlesbudz.jarvis.v2.voice.VoiceInputMode
import com.battlesbudz.jarvis.v2.voice.smartturn.SmartTurnSettings
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the shipping dropdown and its persisted upgrade boundary, without ASR downloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceInputSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("voice_input", Context.MODE_PRIVATE)
    private val visible = mutableStateOf(true)
    private val enabled = mutableStateOf(true)
    private val busyChanges = mutableListOf<Boolean>()
    private val smartTurnDirectory get() = File(context.filesDir, "voice-models/smart-turn-v3.2")

    private fun assertNoSmartTurnDownload() {
        assertFalse("Toggling endpoint control must not start setup", busyChanges.any { it })
        assertFalse("Toggling must not create model or partial-download storage", smartTurnDirectory.exists())
        assertNull(SmartTurnSettings.store(context).availableFile())
    }

    @Before fun reset() { preferences.edit().clear().commit() }
    @After fun cleanup() { preferences.edit().clear().commit() }

    private fun mount() {
        VoiceInputMode.select(context, VoiceInputMode.GEMMA_AUDIO)
        compose.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (visible.value) VoiceInputSettings(enabled.value, onBusy = { busyChanges.add(it) })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun reopenSettings() {
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
    }

    @Test fun upgradeKeepsLegacyOffThenEachDropdownChoiceSurvivesReopening() {
        preferences.edit().putBoolean("gemma_whisper_captions", false).commit()
        mount()
        compose.onNodeWithTag("gemma_caption_engine").assertTextContains("Off")
        assertNull(VoiceInputMode.captionEngine(context))
        for ((tag, engine) in listOf("whisper_npu" to AsrEngine.WHISPER_NPU,
            "whisper" to AsrEngine.WHISPER, "off" to null)) {
            compose.onNodeWithTag("gemma_caption_engine").performScrollTo().performClick()
            compose.onNodeWithTag("gemma_caption_$tag").performClick()
            compose.onNodeWithTag("gemma_caption_$tag").assertDoesNotExist()
            assertEquals(engine, VoiceInputMode.captionEngine(context))
            assertEquals(engine != null, preferences.getBoolean("gemma_whisper_captions", true))
            reopenSettings()
            compose.onNodeWithTag("gemma_caption_engine").assertTextContains(engine?.label ?: "Off")
            assertEquals(engine, VoiceInputMode.captionEngine(context))
        }
    }

    @Test fun anOffChoiceMadeInAnOlderApkWinsOnReturnAfterAnEngineWasSelected() {
        VoiceInputMode.captionEngine(context, AsrEngine.WHISPER_NPU)
        assertEquals(AsrEngine.WHISPER_NPU, VoiceInputMode.captionEngine(context))
        // The older APK knows only this setting, leaving the new engine key intact.
        preferences.edit().putBoolean("gemma_whisper_captions", false).commit()
        mount()
        compose.onNodeWithTag("gemma_caption_engine").assertTextContains("Off")
        assertNull(VoiceInputMode.captionEngine(context))
        compose.onNodeWithTag("gemma_caption_engine").performScrollTo().performClick()
        compose.onNodeWithTag("gemma_caption_whisper_npu").performClick()
        assertEquals(AsrEngine.WHISPER_NPU, VoiceInputMode.captionEngine(context))
        assertTrue(preferences.getBoolean("gemma_whisper_captions", false))
    }

    @Test fun legacyOnAndFreshInstallKeepWhisper() {
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.captionEngine(context))
        preferences.edit().putBoolean("gemma_whisper_captions", true).commit()
        mount()
        compose.onNodeWithTag("gemma_caption_engine").assertTextContains(AsrEngine.WHISPER.label)
        assertFalse("Reading old settings must not replace their stored choice", preferences.contains("caption_engine"))
    }

    @Test fun disablingSettingsDismissesOpenMenuWithoutChangingCaptionChoice() {
        mount()
        compose.onNodeWithTag("gemma_caption_engine").performScrollTo().performClick()
        compose.onNodeWithTag("gemma_caption_off").assertExists()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("gemma_caption_off").assertDoesNotExist()
        compose.onNodeWithTag("gemma_caption_engine").assertIsNotEnabled()
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.captionEngine(context))
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("gemma_caption_off").assertDoesNotExist()
        compose.onNodeWithTag("gemma_caption_engine").performClick()
        compose.onNodeWithTag("gemma_caption_off").performClick()
        assertNull(VoiceInputMode.captionEngine(context))
    }

    @Test fun legacyMicrophoneSettingMigratesWithoutChangingCaptionsAndStaysFixedAfterReopen() {
        preferences.edit().putString("capture_profile", "speech_preserving")
            .putBoolean("gemma_whisper_captions", false).commit()
        mount()
        assertEquals(SpeechCaptureProfile.COMMUNICATION_NOISE_FILTERED, SpeechCaptureProfile.selected(context))
        assertEquals("communication_noise_filtered", preferences.getString("capture_profile", null))
        assertNull(VoiceInputMode.captionEngine(context))
        repeat(2) {
            compose.onNodeWithText("Microphone: Call noise reduction").assertExists()
            compose.onNodeWithText("Use Speech clarity").assertDoesNotExist()
            compose.onNodeWithText("Use Call noise reduction").assertDoesNotExist()
            reopenSettings()
            assertNull(VoiceInputMode.captionEngine(context))
        }
    }

    @Test fun freshSettingsShowFixedMicrophoneProcessingWithoutASelector() {
        mount()
        compose.onNodeWithText("Microphone: Call noise reduction").assertExists()
        compose.onNodeWithText("Use Speech clarity").assertDoesNotExist()
        compose.onNodeWithText("Use Call noise reduction").assertDoesNotExist()
        assertEquals("communication_noise_filtered", preferences.getString("capture_profile", null))
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.captionEngine(context))
    }
    @Test fun smartTurnEndpointDefaultsOnAndControlsCannotChangeDuringCall() {
        // Retired observation-only preferences must not opt out of actual endpoint control.
        preferences.edit().putBoolean("smart_turn_shadow_enabled", false).commit()
        mount()
        compose.onNodeWithTag("smart_turn_endpoint_toggle").performScrollTo().assertTextContains("Disable Smart Turn")
        assertTrue(SmartTurnSettings.enabled(context))
        assertFalse("Reading the enabled default must not write a preference", preferences.contains("smart_turn_endpoint_enabled"))
        compose.onNodeWithTag("smart_turn_model_download").performScrollTo().assertTextContains("Download Smart Turn (8.7 MB)")
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("smart_turn_endpoint_toggle").assertIsNotEnabled()
        compose.onNodeWithTag("smart_turn_model_download").assertIsNotEnabled()
        assertTrue(SmartTurnSettings.enabled(context))
        assertFalse(preferences.getBoolean("smart_turn_shadow_enabled", true))
        assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.selected(context))
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.captionEngine(context))
        assertNoSmartTurnDownload()
    }
    @Test fun disablingSmartTurnPersistsWithoutAnyModelDownload() {
        SmartTurnSettings.setEnabled(context, true)
        mount()
        compose.onNodeWithTag("smart_turn_endpoint_toggle").performScrollTo().assertTextContains("Disable Smart Turn").performClick()
        compose.waitForIdle()
        assertFalse(preferences.getBoolean("smart_turn_endpoint_enabled", true))
        assertFalse(SmartTurnSettings.enabled(context))
        reopenSettings()
        compose.onNodeWithTag("smart_turn_endpoint_toggle").performScrollTo().assertTextContains("Enable Smart Turn")
        assertNoSmartTurnDownload()
        // Re-enabling is also a preference change; the separate download button owns setup.
        compose.onNodeWithTag("smart_turn_endpoint_toggle").performClick()
        compose.waitForIdle()
        assertTrue(preferences.getBoolean("smart_turn_endpoint_enabled", false))
        reopenSettings()
        compose.onNodeWithTag("smart_turn_endpoint_toggle").performScrollTo().assertTextContains("Disable Smart Turn")
        compose.onNodeWithTag("smart_turn_model_download").performScrollTo().assertTextContains("Download Smart Turn (8.7 MB)")
        assertNoSmartTurnDownload()
        assertEquals(VoiceInputMode.GEMMA_AUDIO, VoiceInputMode.selected(context))
        assertEquals(AsrEngine.WHISPER, VoiceInputMode.captionEngine(context))
    }

}
