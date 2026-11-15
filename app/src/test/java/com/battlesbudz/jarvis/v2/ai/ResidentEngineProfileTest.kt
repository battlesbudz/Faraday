package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.actions.MobileActionToolDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Single-engine redesign: every resident-engine creation site (chat
 * prepare, the code-mode borrow, voice-call preparation) builds through
 * [ResidentEngineProfile.forModel], so whichever surface creates the
 * shared slot's engine first, the result is the same voice-configuration
 * engine and no surface ever rebuilds it over capabilities. These tests
 * pin that canonical profile; a real engine cannot exist on the JVM
 * (its construction loads the native runtime), so the profile — the
 * mechanism that guarantees one consistent engine — is what is pinned.
 */
class ResidentEngineProfileTest {

    private fun ResidentEngineProfile.sameConfigurationAs(other: ResidentEngineProfile): Boolean =
        audioEnabled == other.audioEnabled && visionEnabled == other.visionEnabled &&
            tools.map { it.getToolDescriptionJsonString() } ==
            other.tools.map { it.getToolDescriptionJsonString() }

    @Test fun audioModelBuildsWithAudioAndToolsForEverySurface() {
        for (spec in listOf(ModelCatalog.gemma4E2b, ModelCatalog.gemma4E4b)) {
            val profile = ResidentEngineProfile.forModel(spec)
            assertTrue("${spec.id} must wire the audio encoder in", profile.audioEnabled)
            assertFalse("${spec.id} must not build vision without an image turn", profile.visionEnabled)
            assertEquals(
                MobileActionToolDefinitions.all().size, profile.tools.size,
            )
        }
    }

    @Test fun everySurfaceRequestsTheSameResidentConfiguration() {
        val spec = ModelCatalog.gemma4E2b
        // Chat (no image), code mode and voice-call preparation all call
        // forModel with visionRequired = false; their profiles must be
        // configuration-identical, or the second surface would find a
        // resident engine that mismatches its needs and rebuild it.
        val chat = ResidentEngineProfile.forModel(spec, visionRequired = false)
        val code = ResidentEngineProfile.forModel(spec, visionRequired = false)
        val voice = ResidentEngineProfile.forModel(spec, visionRequired = false)
        assertTrue(chat.sameConfigurationAs(code))
        assertTrue(chat.sameConfigurationAs(voice))
    }

    @Test fun textOnlyModelBuildsWithoutAudio() {
        val spec = LocalModelSpec(
            "text-only", "text-only.litertlm", recommendedGpu = false,
            supportsAudio = false, supportsTools = false,
        )
        val profile = ResidentEngineProfile.forModel(spec)
        assertFalse(profile.audioEnabled)
        assertTrue(profile.tools.isEmpty())
    }

    @Test fun toolCapableTextModelStillGetsToolsWithoutAudio() {
        val spec = LocalModelSpec(
            "text-tools", "text-tools.litertlm", recommendedGpu = false,
            supportsAudio = false, supportsTools = true,
        )
        val profile = ResidentEngineProfile.forModel(spec)
        assertFalse(profile.audioEnabled)
        assertEquals(MobileActionToolDefinitions.all().size, profile.tools.size)
    }

    @Test fun visionIsBuiltOnlyWhenAnImageTurnRequiresItAndTheModelSupportsIt() {
        assertTrue(
            ResidentEngineProfile.forModel(ModelCatalog.gemma4E2b, visionRequired = true).visionEnabled
        )
        val noVision = LocalModelSpec(
            "no-vision", "no-vision.litertlm", recommendedGpu = false, supportsVision = false,
        )
        assertFalse(ResidentEngineProfile.forModel(noVision, visionRequired = true).visionEnabled)
    }
}
