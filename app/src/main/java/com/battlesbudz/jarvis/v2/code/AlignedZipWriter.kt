package com.battlesbudz.jarvis.v2.code

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The exporter's zip writer (code-and-export scope section 5.2, step 2):
 * a small hand-rolled writer — the exporter does not use commons-compress
 * or [java.util.zip.ZipOutputStream] — because Android requires
 * `resources.arsc` STORED and 4-byte aligned in the APK, and this writer
 * guarantees alignment for every STORED entry it writes.
 *
 * Alignment is done the zipalign way: when a STORED entry's data would
 * not start on a 4-byte boundary, the local header grows a padding
 * extra field (id 0xFFFF) sized so the data lands exactly on the
 * boundary. Central-directory extra fields stay empty; alignment is a
 * local-header property. Entry timestamps are a fixed DOS date, so the
 * same inputs produce byte-identical output.
 */
internal class AlignedZipWriter(private val out: OutputStream) {

    private data class CentralEntry(
        val nameBytes: ByteArray,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long
    )

    private val central = mutableListOf<CentralEntry>()
    private var written: Long = 0
    private var finished = false

    fun addStored(name: String, data: ByteArray) = add(name, data, METHOD_STORED)

    fun addDeflated(name: String, data: ByteArray) = add(name, data, METHOD_DEFLATED)

    private fun add(name: String, data: ByteArray, method: Int) {
        check(!finished) { "This writer is already finished" }
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val payload = if (method == METHOD_DEFLATED) deflate(data) else data
        val localHeaderOffset = written
        val extra = if (method == METHOD_STORED) alignmentExtra(written, nameBytes.size) else ByteArray(0)

        val header = ByteArray(LOCAL_HEADER_SIZE)
        putU32(header, 0, LOCAL_HEADER_SIGNATURE)
        putU16(header, 4, if (method == METHOD_STORED) VERSION_STORED else VERSION_DEFLATED)
        putU16(header, 6, FLAG_UTF8_NAMES)
        putU16(header, 8, method)
        putU16(header, 10, DOS_TIME)
        putU16(header, 12, DOS_DATE)
        putU32(header, 14, crc.toInt())
        putU32(header, 18, payload.size)
        putU32(header, 22, data.size)
        putU16(header, 26, nameBytes.size)
        putU16(header, 28, extra.size)
        write(header)
        write(nameBytes)
        write(extra)
        write(payload)
        central += CentralEntry(nameBytes, method, crc, payload.size.toLong(), data.size.toLong(), localHeaderOffset)
    }

    /** Writes the central directory and the end record. Call exactly once. */
    fun finish() {
        check(!finished) { "This writer is already finished" }
        finished = true
        val centralStart = written
        for (entry in central) {
            val header = ByteArray(CENTRAL_HEADER_SIZE)
            putU32(header, 0, CENTRAL_HEADER_SIGNATURE)
            putU16(header, 4, VERSION_MADE_BY)
            putU16(header, 6, if (entry.method == METHOD_STORED) VERSION_STORED else VERSION_DEFLATED)
            putU16(header, 8, FLAG_UTF8_NAMES)
            putU16(header, 10, entry.method)
            putU16(header, 12, DOS_TIME)
            putU16(header, 14, DOS_DATE)
            putU32(header, 16, entry.crc.toInt())
            putU32(header, 20, entry.compressedSize.toInt())
            putU32(header, 24, entry.size.toInt())
            putU16(header, 28, entry.nameBytes.size)
            // Extra, comment, disk, and attribute fields all stay zero:
            // alignment lives in the local header only.
            putU32(header, 42, entry.localHeaderOffset.toInt())
            write(header)
            write(entry.nameBytes)
        }
        val centralSize = written - centralStart
        val end = ByteArray(END_RECORD_SIZE)
        putU32(end, 0, END_SIGNATURE)
        putU16(end, 8, central.size)
        putU16(end, 10, central.size)
        putU32(end, 12, centralSize.toInt())
        putU32(end, 16, centralStart.toInt())
        write(end)
        out.flush()
    }

    /**
     * The padding extra field that puts a STORED entry's data on a
     * 4-byte boundary: when the bare header end needs [pad] more bytes
     * (1 to 3), the field is pad + 4 long — a real (id, size) header
     * plus pad zero bytes — so the field itself stays well-formed.
     */
    private fun alignmentExtra(headerStart: Long, nameBytesSize: Int): ByteArray {
        val headerEnd = headerStart + LOCAL_HEADER_SIZE + nameBytesSize
        val pad = ((4 - (headerEnd % 4)) % 4).toInt()
        if (pad == 0) return ByteArray(0)
        val extra = ByteArray(pad + 4)
        putU16(extra, 0, EXTRA_PADDING_ID)
        putU16(extra, 2, pad)
        return extra
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            deflater.setInput(data)
            deflater.finish()
            val buffer = ByteArray(maxOf(1024, data.size / 2))
            val out = java.io.ByteArrayOutputStream()
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        written += bytes.size
    }

    companion object {
        const val METHOD_STORED = 0
        const val METHOD_DEFLATED = 8

        private const val LOCAL_HEADER_SIGNATURE = 0x04034b50
        private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50
        private const val END_SIGNATURE = 0x06054b50
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_RECORD_SIZE = 22
        private const val VERSION_STORED = 10
        private const val VERSION_DEFLATED = 20
        private const val VERSION_MADE_BY = 20
        private const val FLAG_UTF8_NAMES = 0x0800
        private const val EXTRA_PADDING_ID = 0xFFFF
        private const val DOS_TIME = 0

        /** 1980-01-01, the zip epoch: fixed so exports are reproducible. */
        private const val DOS_DATE = 0x21
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = (value shr 8 and 0xFF).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = (value shr 8 and 0xFF).toByte()
        bytes[offset + 2] = (value shr 16 and 0xFF).toByte()
        bytes[offset + 3] = (value shr 24 and 0xFF).toByte()
    }
}
