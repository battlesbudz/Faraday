package com.battlesbudz.jarvis.v2.code

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The aligned zip writer's properties (scope section 6, Unit 5 tests):
 * entries round-trip, STORED entries (resources.arsc above all) start
 * on 4-byte boundaries, and the same inputs give the same bytes.
 */
class AlignedZipWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun entriesRoundTripThroughZipFile() {
        val bytes = buildZip()
        val file = File(tmp.root, "round-trip.zip")
        file.writeBytes(bytes)
        ZipFile(file).use { zip ->
            assertEquals(
                setOf("resources.arsc", "classes.dex", "assets/www/index.html", "a"),
                zip.entries().toList().map { it.name }.toSet()
            )
            assertArrayEquals(ARSC, zip.getInputStream(zip.getEntry("resources.arsc")).use { it.readBytes() })
            assertArrayEquals(DEX, zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() })
            assertArrayEquals(HTML, zip.getInputStream(zip.getEntry("assets/www/index.html")).use { it.readBytes() })
            assertArrayEquals(byteArrayOf(0x55), zip.getInputStream(zip.getEntry("a")).use { it.readBytes() })
        }
    }

    @Test
    fun storedEntriesStartOnFourByteBoundaries() {
        val entries = localZipEntries(buildZip())
        val stored = entries.filter { it.method == ZipEntry.STORED }
        assertEquals(setOf("resources.arsc", "a"), stored.map { it.name }.toSet())
        for (entry in stored) {
            assertEquals("Stored entry ${entry.name} must start 4-byte aligned", 0, entry.dataStart % 4)
        }
        val arsc = entries.single { it.name == "resources.arsc" }
        assertEquals(ZipEntry.STORED, arsc.method)
    }

    @Test
    fun deflatedEntriesKeepTheirMethod() {
        val entries = localZipEntries(buildZip())
        val deflated = entries.filter { it.method == ZipEntry.DEFLATED }
        assertEquals(setOf("classes.dex", "assets/www/index.html"), deflated.map { it.name }.toSet())
    }

    @Test
    fun sameInputsProduceIdenticalBytes() {
        assertArrayEquals(buildZip(), buildZip())
    }

    private fun buildZip(): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = AlignedZipWriter(out)
        writer.addStored("resources.arsc", ARSC)
        writer.addDeflated("classes.dex", DEX)
        writer.addDeflated("assets/www/index.html", HTML)
        writer.addStored("a", byteArrayOf(0x55))
        writer.finish()
        return out.toByteArray()
    }

    private companion object {
        val ARSC = ByteArray(101) { index -> (index * 31 % 256).toByte() }
        val DEX = "dex fixture payload, long enough to be worth deflating, deflating, deflating"
            .toByteArray(Charsets.UTF_8)
        val HTML = "<!DOCTYPE html><html><body>hi</body></html>".toByteArray(Charsets.UTF_8)
    }
}
