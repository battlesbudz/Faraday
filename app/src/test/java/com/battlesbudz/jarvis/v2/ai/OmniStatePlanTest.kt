package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class OmniStatePlanTest {
    @Test fun buildCarriesGeometryAndTotals() {
        val plan = OmniStatePlan.build(
            stateNames = listOf("kv_cache_c_0", "kv_cache_k_2"),
            stateElements = mapOf("kv_cache_c_0" to 12_288, "kv_cache_k_2" to 2_097_152),
            hiddenSize = 2048,
            cacheLength = 4096,
        )

        assertEquals(12_288, plan.stateElements.getValue("kv_cache_c_0"))
        assertEquals(2_097_152, plan.stateElements.getValue("kv_cache_k_2"))
        assertEquals(2048, plan.hiddenSize)
        assertEquals(4096, plan.cacheLength)
        assertEquals((12_288L + 2_097_152L) * 4L, plan.totalStateBytes)
    }

    @Test fun buildRejectsAMissingStateTensor() {
        assertBuildFails {
            OmniStatePlan.build(listOf("a", "b"), mapOf("a" to 1), hiddenSize = 1, cacheLength = 1)
        }
    }

    @Test fun buildRejectsAnExtraStateTensor() {
        assertBuildFails {
            OmniStatePlan.build(listOf("a"), mapOf("a" to 1, "b" to 1), hiddenSize = 1, cacheLength = 1)
        }
    }

    @Test fun buildRejectsNonPositiveGeometry() {
        assertBuildFails {
            OmniStatePlan.build(listOf("a"), mapOf("a" to 0), hiddenSize = 1, cacheLength = 1)
        }
        assertBuildFails {
            OmniStatePlan.build(listOf("a"), mapOf("a" to 1), hiddenSize = 0, cacheLength = 1)
        }
        assertBuildFails {
            OmniStatePlan.build(listOf("a"), mapOf("a" to 1), hiddenSize = 1, cacheLength = 0)
        }
        assertBuildFails {
            OmniStatePlan.build(emptyList(), emptyMap(), hiddenSize = 1, cacheLength = 1)
        }
    }

    @Test fun pinnedBundleGeometryMatchesTheDecoderGraph() {
        // The values pinned in lfm3_omni_sections.json, read out of the
        // pinned bundle's decoder flatbuffer (decode signature): 13
        // short-convolution states of [1, 2048, 6] and 22 attention caches
        // of [1, 4, 4096, 128] (keys) / [1, 4, 128, 4096] (values), all
        // FLOAT32; embeddings [1, 1, 2048], mask [1, 1, 1, 4096].
        val conv = listOf(0, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23).map { "kv_cache_c_$it" }
        val attn = listOf(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22)
        val names = conv + attn.map { "kv_cache_k_$it" } + attn.map { "kv_cache_v_$it" }
        val elements = names.associateWith { if (it.startsWith("kv_cache_c_")) 12_288 else 2_097_152 }

        val plan = OmniStatePlan.build(names, elements, hiddenSize = 2048, cacheLength = 4096)

        assertEquals(35, plan.stateElements.size)
        assertEquals(46_297_088L * 4L, plan.totalStateBytes)
    }

    private fun assertBuildFails(build: () -> OmniStatePlan) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // The plan refuses geometry that does not match the state list.
        }
    }
}
