package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OmniCodecStatePlanTest {
    @Test fun buildCarriesSizesForExactCoverage() {
        val sizes = OmniCodecStatePlan.build(
            stateNames = listOf("c0", "k0"),
            stateElements = mapOf("c0" to 3_072, "k0" to 3_712),
        )

        assertEquals(3_072, sizes.getValue("c0"))
        assertEquals(3_712, sizes.getValue("k0"))
    }

    @Test fun buildRejectsAMissingStateTensor() {
        assertBuildFails {
            OmniCodecStatePlan.build(listOf("c0", "k0"), mapOf("c0" to 3_072))
        }
    }

    @Test fun buildRejectsAnExtraStateTensor() {
        assertBuildFails {
            OmniCodecStatePlan.build(listOf("c0"), mapOf("c0" to 3_072, "k0" to 3_712))
        }
    }

    @Test fun buildRejectsNonPositiveOrEmptyGeometry() {
        assertBuildFails {
            OmniCodecStatePlan.build(listOf("c0"), mapOf("c0" to 0))
        }
        assertBuildFails {
            OmniCodecStatePlan.build(emptyList(), emptyMap())
        }
    }

    @Test fun pinnedGraphGeometryMatchesTheCodecGraph() {
        // The values pinned in lfm3_omni_sections.json, read out of the
        // pinned codec graph's flatbuffer (decode signature): the five
        // short-convolution states c0–c4 are [1, 6, 512] FLOAT32 and the
        // six attention caches k0–k2 / v0–v2 are [1, 2, 29, 64] FLOAT32.
        val names = listOf("c0", "c1", "c2", "c3", "c4", "k0", "k1", "k2", "v0", "v1", "v2")
        val elements = names.associateWith { if (it.startsWith("c")) 3_072 else 3_712 }

        val sizes = OmniCodecStatePlan.build(names, elements)

        assertEquals(11, sizes.size)
        assertEquals(5 * 3_072 + 6 * 3_712, sizes.values.sum())
    }

    private fun assertBuildFails(build: () -> Map<String, Int>) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The plan refuses geometry that does not match the state list.
        }
    }
}
