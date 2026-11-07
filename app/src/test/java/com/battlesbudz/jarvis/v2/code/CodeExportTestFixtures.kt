package com.battlesbudz.jarvis.v2.code

import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Fixtures for the Unit 5 exporter tests: a hand-built binary XML
 * string pool, a minimal stand-in shell APK, the local-header walk the
 * alignment assertions use, and the test-only signing keystore.
 *
 * The keystore (`src/test/resources/code-export/test-signing.p12`,
 * alias `faraday-code-export`) is a throwaway key generated for these
 * tests only, mirroring the production alias and certificate subject.
 * It signs nothing real: production exports use the AndroidKeyStore
 * key from [CodeExportSigningKey], which never leaves the phone.
 */

internal fun axmlWithStrings(strings: List<String>, utf8: Boolean): ByteArray {
    val slots = strings.map { if (utf8) utf8Slot(it) else utf16Slot(it) }
    val stringsStart = 28 + 4 * strings.size
    val chunkSize = stringsStart + slots.sumOf { it.size }
    val out = ByteArrayOutputStream()
    // File header: RES_XML_TYPE.
    writeU16(out, 0x0003)
    writeU16(out, 8)
    writeU32(out, 8 + chunkSize)
    // String pool chunk.
    writeU16(out, 0x0001)
    writeU16(out, 28)
    writeU32(out, chunkSize)
    writeU32(out, strings.size)
    writeU32(out, 0)
    writeU32(out, if (utf8) 0x100 else 0)
    writeU32(out, stringsStart)
    writeU32(out, 0)
    var offset = 0
    for (slot in slots) {
        writeU32(out, offset)
        offset += slot.size
    }
    for (slot in slots) out.write(slot)
    return out.toByteArray()
}

private fun utf16Slot(value: String): ByteArray {
    val out = ByteArrayOutputStream()
    writeU16(out, value.length)
    out.write(value.toByteArray(Charsets.UTF_16LE))
    writeU16(out, 0)
    return out.toByteArray()
}

private fun utf8Slot(value: String): ByteArray {
    val body = value.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream()
    writeUtf8Length(out, value.length)
    writeUtf8Length(out, body.size)
    out.write(body)
    out.write(0)
    return out.toByteArray()
}

private fun writeUtf8Length(out: ByteArrayOutputStream, value: Int) {
    if (value < 0x80) {
        out.write(value)
    } else {
        out.write(0x80 or (value shr 8))
        out.write(value and 0xFF)
    }
}

private fun writeU16(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write(value shr 8 and 0xFF)
}

private fun writeU32(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write(value shr 8 and 0xFF)
    out.write(value shr 16 and 0xFF)
    out.write(value shr 24 and 0xFF)
}

/**
 * A minimal stand-in for the CI-built shell: a manifest carrying the
 * real placeholder strings, a STORED resources.arsc, a deflated dex,
 * and a META-INF entry the exporter must drop.
 */
internal fun fixtureShellApk(utf8Pool: Boolean = false): ByteArray {
    val manifest = axmlWithStrings(
        listOf(
            "android",
            CodeApkExporter.PLACEHOLDER_PACKAGE_NAME,
            "Faraday",
            CodeApkExporter.PLACEHOLDER_LABEL,
            "app"
        ),
        utf8Pool
    )
    val arsc = ByteArray(37) { index -> (index * 7 % 251).toByte() }
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
        zip.write(manifest)
        zip.closeEntry()
        val arscEntry = ZipEntry("resources.arsc")
        arscEntry.method = ZipEntry.STORED
        arscEntry.size = arsc.size.toLong()
        val crc = CRC32()
        crc.update(arsc)
        arscEntry.crc = crc.value
        zip.putNextEntry(arscEntry)
        zip.write(arsc)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("classes.dex"))
        zip.write("dex fixture bytes".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
        zip.write("Manifest-Version: 1.0\r\n".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    return out.toByteArray()
}

/** One local file header's facts, for alignment assertions. */
internal data class LocalZipEntry(val name: String, val method: Int, val dataStart: Int, val dataSize: Int)

/** Walks a zip's local headers in order; stops at the first non-local signature. */
internal fun localZipEntries(zip: ByteArray): List<LocalZipEntry> {
    fun u16(offset: Int): Int =
        (zip[offset].toInt() and 0xFF) or (zip[offset + 1].toInt() and 0xFF shl 8)
    fun u32(offset: Int): Int =
        (zip[offset].toInt() and 0xFF) or (zip[offset + 1].toInt() and 0xFF shl 8) or
            (zip[offset + 2].toInt() and 0xFF shl 16) or (zip[offset + 3].toInt() and 0xFF shl 24)
    val entries = mutableListOf<LocalZipEntry>()
    var pos = 0
    while (pos + 30 <= zip.size && u32(pos) == 0x04034b50) {
        val method = u16(pos + 8)
        val compressedSize = u32(pos + 18)
        val nameLength = u16(pos + 26)
        val extraLength = u16(pos + 28)
        val name = String(zip, pos + 30, nameLength, Charsets.UTF_8)
        val dataStart = pos + 30 + nameLength + extraLength
        entries += LocalZipEntry(name, method, dataStart, compressedSize)
        pos = dataStart + compressedSize
    }
    return entries
}

internal const val TEST_KEYSTORE_PASSWORD = "faraday-test"
internal const val TEST_KEY_ALIAS = "faraday-code-export"

internal fun loadTestKeyStore(): KeyStore {
    val keyStore = KeyStore.getInstance("PKCS12")
    val stream = CodeApkExporterTest::class.java.getResourceAsStream("/code-export/test-signing.p12")
        ?: error("The test signing fixture is missing from the test resources")
    stream.use { keyStore.load(it, TEST_KEYSTORE_PASSWORD.toCharArray()) }
    return keyStore
}
