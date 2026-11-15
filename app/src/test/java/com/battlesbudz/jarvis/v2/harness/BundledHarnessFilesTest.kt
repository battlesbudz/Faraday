package com.battlesbudz.jarvis.v2.harness

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Pins the shipped asset files (`app/src/main/assets/harness/v1/`) to the
 * compiled-in defaults: the two representations of the v1 harness must
 * stay byte-identical in effect, or a fresh install would behave
 * differently depending on which fallback layer served the config.
 */
class BundledHarnessFilesTest {
    @Test fun shippedAssetsParseExactlyToCompiledDefaults() {
        val dir = locateAssets()
        val files = HarnessConfigParser.FILES.associateWith { name ->
            File(dir, name).takeIf { it.isFile }?.readText()
        }
        assertTrue("all bundled files present", files.values.all { it != null })
        val resolved = HarnessConfigParser.resolve(1, emptyMap(), files)
        assertEquals(HarnessConfig.DEFAULT, resolved)
    }

    @Test fun shippedVoiceFileIsTheDefaultTextPlusNewline() {
        val text = File(locateAssets(), HarnessConfigParser.FILE_VOICE_INSTRUCTIONS).readText()
        assertEquals(HarnessConfig.DEFAULT.voiceInstructions + "\n", text)
    }

    private fun locateAssets(): File {
        val relative = "src/main/assets/harness/v1"
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(4) {
            val fromModule = File(dir, relative)
            if (fromModule.isDirectory) return fromModule
            val fromRoot = File(dir, "app/$relative")
            if (fromRoot.isDirectory) return fromRoot
            dir = dir?.parentFile
        }
        fail("could not locate $relative from ${System.getProperty("user.dir") ?: "."}")
        throw AssertionError("unreachable")
    }
}
