package com.battlesbudz.jarvis.v2.code

import java.io.ByteArrayOutputStream

/**
 * In-place patching of an Android binary XML string pool (code-and-export
 * scope section 5.2, step 3). The export shell's manifest is built with
 * placeholder package and label strings sized to be the longest values
 * any export will ever need; the exporter replaces those exact strings
 * with the project's real ones and zero-fills the remainder of each
 * slot, so no offset moves anywhere in the file and every other byte is
 * untouched.
 *
 * Both pool encodings are handled (aapt2 emits UTF-16 and UTF-8 pools):
 * a replacement is re-encoded in the pool's own encoding and must fit
 * the placeholder's slot — encoded length, length prefixes, and
 * terminator included. A replacement that does not fit, a placeholder
 * that is not present, or input that is not binary XML at all fails
 * with [PatchException]: exports never silently truncate (section 5.2,
 * step 1).
 */
internal object BinaryXmlStringPoolPatcher {

    class PatchException(message: String) : Exception(message)

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val UTF8_FLAG = 0x00000100
    private const val POOL_HEADER_SIZE = 28

    /**
     * Returns a copy of [manifest] with every string equal to a key of
     * [replacements] replaced by its value. All other bytes are
     * identical to the input, and the result has the same length.
     */
    fun patch(manifest: ByteArray, replacements: Map<String, String>): ByteArray {
        require(replacements.isNotEmpty()) { "No replacements given" }
        val pool = Pool(manifest)
        val result = manifest.copyOf()
        val replaced = mutableSetOf<String>()
        for (index in 0 until pool.stringCount) {
            val slot = pool.slot(index)
            val replacement = replacements[slot.text] ?: continue
            val encoded = if (pool.utf8) encodeUtf8Slot(replacement) else encodeUtf16Slot(replacement)
            if (encoded.size > slot.length) {
                throw PatchException(
                    "\"$replacement\" needs ${encoded.size} bytes in the manifest string pool; " +
                        "the placeholder it replaces has ${slot.length}. Exports never truncate: " +
                        "the value exceeds its placeholder."
                )
            }
            System.arraycopy(encoded, 0, result, slot.start, encoded.size)
            result.fill(0, slot.start + encoded.size, slot.start + slot.length)
            replaced += slot.text
        }
        val missing = replacements.keys - replaced
        if (missing.isNotEmpty()) {
            throw PatchException(
                "Placeholder string(s) not found in the shell manifest: " +
                    missing.joinToString(", ") { "\"$it\"" } +
                    ". The shell and the exporter disagree about the placeholders."
            )
        }
        return result
    }

    /** Every string in the manifest's pool, in pool order. */
    fun stringsOf(manifest: ByteArray): List<String> {
        val pool = Pool(manifest)
        return (0 until pool.stringCount).map { pool.slot(it).text }
    }

    private class Slot(val start: Int, val length: Int, val text: String)

    private class Pool(private val bytes: ByteArray) {
        val stringCount: Int
        val utf8: Boolean
        private val chunkStart: Int
        private val headerSize: Int
        private val stringsStart: Int
        private val dataEnd: Int

        init {
            if (bytes.size < 8 || u16(bytes, 0) != RES_XML_TYPE) {
                throw PatchException("Not an Android binary XML file")
            }
            var offset = 8
            var found = -1
            var foundSize = 0
            while (offset + 8 <= bytes.size) {
                val type = u16(bytes, offset)
                val size = u32(bytes, offset + 4)
                if (size < 8 || offset + size > bytes.size) {
                    throw PatchException("Corrupt chunk header at offset $offset")
                }
                if (type == RES_STRING_POOL_TYPE) {
                    found = offset
                    foundSize = size
                    break
                }
                offset += size
            }
            if (found < 0) throw PatchException("Binary XML has no string pool")
            chunkStart = found
            if (foundSize < POOL_HEADER_SIZE) throw PatchException("String pool chunk is truncated")
            headerSize = u16(bytes, chunkStart + 2)
            stringCount = u32(bytes, chunkStart + 8)
            val styleCount = u32(bytes, chunkStart + 12)
            val flags = u32(bytes, chunkStart + 16)
            utf8 = flags and UTF8_FLAG != 0
            stringsStart = u32(bytes, chunkStart + 20)
            val stylesStart = u32(bytes, chunkStart + 24)
            dataEnd = chunkStart + if (styleCount > 0 && stylesStart > 0) stylesStart else foundSize
        }

        fun slot(index: Int): Slot {
            val offsetAt = chunkStart + headerSize + 4 * index
            val start = chunkStart + stringsStart + u32(bytes, offsetAt)
            val end = if (index + 1 < stringCount) {
                chunkStart + stringsStart + u32(bytes, offsetAt + 4)
            } else {
                dataEnd
            }
            if (start < chunkStart || end < start || end > bytes.size) {
                throw PatchException("String pool entry $index lies outside the file")
            }
            val text = if (utf8) decodeUtf8(start) else decodeUtf16(start)
            return Slot(start, end - start, text)
        }

        private fun decodeUtf16(start: Int): String {
            var pos = start
            var length = u16(bytes, pos)
            pos += 2
            if (length and 0x8000 != 0) {
                length = (length and 0x7FFF shl 16) or u16(bytes, pos)
                pos += 2
            }
            val charBytes = ByteArray(length * 2)
            System.arraycopy(bytes, pos, charBytes, 0, charBytes.size)
            return String(charBytes, Charsets.UTF_16LE)
        }

        private fun decodeUtf8(start: Int): String {
            var pos = start
            pos += utf8LengthSize(pos) // UTF-16 character count; not needed to decode
            val byteLengthAndSize = readUtf8Length(pos)
            pos += byteLengthAndSize.second
            return String(bytes, pos, byteLengthAndSize.first, Charsets.UTF_8)
        }
    }

    /** The encoded slot (length prefixes, body, terminator) for [value] in a UTF-16 pool. */
    private fun encodeUtf16Slot(value: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (value.length < 0x8000) {
            writeU16(out, value.length)
        } else {
            writeU16(out, 0x8000 or (value.length shr 16))
            writeU16(out, value.length and 0xFFFF)
        }
        out.write(value.toByteArray(Charsets.UTF_16LE))
        writeU16(out, 0)
        return out.toByteArray()
    }

    /** The encoded slot (both length prefixes, body, terminator) for [value] in a UTF-8 pool. */
    private fun encodeUtf8Slot(value: String): ByteArray {
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

    /** (value, bytes consumed) for a UTF-8 pool length at [pos]. */
    private fun readUtf8Length(bytes: ByteArray, pos: Int): Pair<Int, Int> {
        val first = bytes[pos].toInt() and 0xFF
        return if (first and 0x80 == 0) {
            first to 1
        } else {
            ((first and 0x7F) shl 8 or (bytes[pos + 1].toInt() and 0xFF)) to 2
        }
    }

    private fun utf8LengthSize(bytes: ByteArray, pos: Int): Int = readUtf8Length(bytes, pos).second

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write(value shr 8 and 0xFF)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() and 0xFF shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            (bytes[offset + 1].toInt() and 0xFF shl 8) or
            (bytes[offset + 2].toInt() and 0xFF shl 16) or
            (bytes[offset + 3].toInt() and 0xFF shl 24)
}
