package com.battlesbudz.jarvis.v2.conversation

import java.io.File
import java.util.Properties
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSurfaceGovernanceTest {
    @Test fun unfilledPlaceholderIsRejected() {
        assertRejected("unfilled placeholder") {
            PromptSurfaceGovernance.requireNoUnfilledPlaceholders("Hello \${user_name}")
        }
    }

    @Test fun systemLayerRejectsKnownToolNames() {
        assertRejected("system layer tool name") {
            PromptSurfaceGovernance.requireSystemLayerHasNoToolNames(
                "You may call open_app when useful.", listOf("open_app", "set_volume"))
        }
    }

    @Test fun changedOrUnpinnedPromptSizeIsRejected() {
        assertRejected("changed size") {
            PromptSurfaceGovernance.requirePinnedSizes(mapOf("identity.kt" to 10), mapOf("identity.kt" to 11))
        }
        assertRejected("unpinned surface") {
            PromptSurfaceGovernance.requirePinnedSizes(mapOf("identity.kt" to 10),
                mapOf("identity.kt" to 10, "new-prompt.kt" to 4))
        }
    }

    @Test fun checkedInPromptSourcesMatchGoldenByteSizes() {
        val repositoryRoot = if (File("app/src/test/resources/prompt-surface-sizes.properties").exists()) {
            File(".")
        } else {
            File("..")
        }
        val golden = Properties().apply {
            repositoryRoot.resolve("app/src/test/resources/prompt-surface-sizes.properties")
                .inputStream().use { load(it) }
        }.entries.associate { it.key.toString() to it.value.toString().toInt() }
        val actual = golden.keys.associateWith { path -> repositoryRoot.resolve(path).readBytes().size }
        PromptSurfaceGovernance.requirePinnedSizes(golden, actual)
        assertTrue("The golden inventory must contain prompt sources", golden.isNotEmpty())
    }

    private fun assertRejected(label: String, block: () -> Unit) {
        try {
            block()
            fail("Expected invariant rejection: $label")
        } catch (expected: IllegalArgumentException) {
            // The negative specimen proves the guard fails closed.
        }
    }
}
