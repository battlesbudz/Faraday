package com.battlesbudz.jarvis.v2.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest patcher against byte fixtures of a binary XML string
 * pool, one per encoding (scope section 6, Unit 5 tests): replacements
 * land in the right slots, no offset or length moves, a too-long
 * replacement is refused, and a missing placeholder is reported.
 */
class BinaryXmlStringPoolPatcherTest {

    private val packagePlaceholder = CodeApkExporter.PLACEHOLDER_PACKAGE_NAME
    private val labelPlaceholder = CodeApkExporter.PLACEHOLDER_LABEL

    @Test
    fun utf16PoolPatchReplacesBothPlaceholders() {
        val manifest = axmlWithStrings(strings(utf8 = false), utf8 = false)
        val patched = BinaryXmlStringPoolPatcher.patch(
            manifest,
            mapOf(packagePlaceholder to NEW_PACKAGE, labelPlaceholder to NEW_LABEL)
        )
        assertEquals(manifest.size, patched.size)
        assertEquals(
            listOf("android", NEW_PACKAGE, "Faraday", NEW_LABEL, "app"),
            BinaryXmlStringPoolPatcher.stringsOf(patched)
        )
    }

    @Test
    fun utf8PoolPatchReplacesBothPlaceholders() {
        val manifest = axmlWithStrings(strings(utf8 = true), utf8 = true)
        val patched = BinaryXmlStringPoolPatcher.patch(
            manifest,
            mapOf(packagePlaceholder to NEW_PACKAGE, labelPlaceholder to NEW_LABEL)
        )
        assertEquals(manifest.size, patched.size)
        assertEquals(
            listOf("android", NEW_PACKAGE, "Faraday", NEW_LABEL, "app"),
            BinaryXmlStringPoolPatcher.stringsOf(patched)
        )
    }

    @Test
    fun identityReplacementIsByteIdenticalInBothEncodings() {
        for (utf8 in listOf(false, true)) {
            val manifest = axmlWithStrings(strings(utf8), utf8)
            val patched = BinaryXmlStringPoolPatcher.patch(
                manifest,
                mapOf(packagePlaceholder to packagePlaceholder, labelPlaceholder to labelPlaceholder)
            )
            assertTrue(
                "Patching a placeholder with itself must not move a byte (utf8=$utf8)",
                manifest.contentEquals(patched)
            )
        }
    }

    @Test
    fun aShorterReplacementZeroFillsTheRestOfItsSlot() {
        val manifest = axmlWithStrings(strings(utf8 = false), utf8 = false)
        val patched = BinaryXmlStringPoolPatcher.patch(manifest, mapOf(labelPlaceholder to "Tiny"))
        assertEquals(manifest.size, patched.size)
        assertEquals("Tiny", BinaryXmlStringPoolPatcher.stringsOf(patched)[3])
        // The pool still parses end to end, and the following string is intact.
        assertEquals("app", BinaryXmlStringPoolPatcher.stringsOf(patched)[4])
    }

    @Test
    fun replacementThatOutgrowsTheSlotIsRefusedInBothEncodings() {
        for (utf8 in listOf(false, true)) {
            val manifest = axmlWithStrings(strings(utf8), utf8)
            val tooLong = "x".repeat(CodeApkExporter.PACKAGE_PLACEHOLDER_CHARS + 1)
            val failure = assertThrows(BinaryXmlStringPoolPatcher.PatchException::class.java) {
                BinaryXmlStringPoolPatcher.patch(manifest, mapOf(packagePlaceholder to tooLong))
            }
            assertTrue(failure.message!!.contains("never truncate"))
        }
    }

    @Test
    fun missingPlaceholderIsRefused() {
        val manifest = axmlWithStrings(listOf("android", packagePlaceholder, "app"), utf8 = false)
        val failure = assertThrows(BinaryXmlStringPoolPatcher.PatchException::class.java) {
            BinaryXmlStringPoolPatcher.patch(
                manifest,
                mapOf(packagePlaceholder to NEW_PACKAGE, labelPlaceholder to NEW_LABEL)
            )
        }
        assertTrue(failure.message!!.contains("not found"))
        assertTrue(failure.message!!.contains(labelPlaceholder))
    }

    @Test
    fun nonBinaryXmlIsRefused() {
        assertThrows(BinaryXmlStringPoolPatcher.PatchException::class.java) {
            BinaryXmlStringPoolPatcher.patch("<?xml version=\"1.0\"?><manifest/>".toByteArray(), mapOf("a" to "b"))
        }
        assertThrows(BinaryXmlStringPoolPatcher.PatchException::class.java) {
            BinaryXmlStringPoolPatcher.stringsOf(ByteArray(0))
        }
    }

    private fun strings(utf8: Boolean): List<String> =
        listOf("android", packagePlaceholder, "Faraday", labelPlaceholder, "app")

    private companion object {
        const val NEW_PACKAGE = "com.battlesbudz.faraday.app.snake_game_0123abcd"
        const val NEW_LABEL = "Snake Game"
    }
}
