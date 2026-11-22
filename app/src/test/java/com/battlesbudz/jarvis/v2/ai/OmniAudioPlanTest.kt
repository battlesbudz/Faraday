package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OmniAudioPlanTest {
    @Test fun buildCarriesGeometry() {
        val plan = OmniAudioPlan.build(windowSamples = 480_000, hiddenSize = 2048)

        assertEquals(480_000, plan.windowSamples)
        assertEquals(2048, plan.hiddenSize)
    }

    @Test fun buildRejectsNonPositiveGeometry() {
        assertBuildFails { OmniAudioPlan.build(windowSamples = 0, hiddenSize = 2048) }
        assertBuildFails { OmniAudioPlan.build(windowSamples = 480_000, hiddenSize = 0) }
        assertBuildFails { OmniAudioPlan.build(windowSamples = -1, hiddenSize = 2048) }
    }

    @Test fun pinnedBundleGeometryMatchesTheAudioGraph() {
        // The values pinned in lfm3_omni_sections.json, read out of the
        // pinned bundle's audio flatbuffer (encode signature): input
        // `audio` [1, 3000, 160] FLOAT32 = 480,000 elements, output
        // `features` [1, 375, 2048] FLOAT32 — hidden size 2048, matching
        // the language model's hidden size the features feed into.
        val plan = OmniAudioPlan.build(windowSamples = 480_000, hiddenSize = 2048)

        assertEquals(3000 * 160, plan.windowSamples)
        assertEquals(2048, plan.hiddenSize)
    }

    private fun assertBuildFails(build: () -> OmniAudioPlan) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The plan refuses non-positive geometry.
        }
    }
}
