package com.battlesbudz.jarvis.v2.runtime.turn

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TfliteSignatureInputsTest {
    @Test fun readsCodecDecodeInputsFromThePinnedGraphLayout() {
        // The pinned codec graph (152,370,912 bytes, SHA-256 7134523f…)
        // places the `codes` TensorMap's vtable at byte 152,364,902, six
        // KB from the end of the file — the layout that defeated the old
        // head-window reader. The resources carry the file's first and
        // last 8 KiB; every structure the reader follows lives in one of
        // the two slices, reassembled here at the real file length.
        val file = assemblePinnedCodecFile()
        try {
            val inputs = TfliteSignatureInputs.inputsFor(file, "decode")

            assertEquals(
                listOf(
                    "c0", "c1", "c2", "c3", "c4", "codes",
                    "k0", "k1", "k2", "start", "v0", "v1", "v2",
                ),
                inputs,
            )
            // The call owner's filter: everything but the per-call inputs
            // is codec state, in signature order.
            assertEquals(
                listOf("c0", "c1", "c2", "c3", "c4", "k0", "k1", "k2", "v0", "v1", "v2"),
                inputs.filter { it != "codes" && it != "start" },
            )
        } finally {
            file.delete()
        }
    }

    @Test fun unknownSignatureFails() {
        val file = assemblePinnedCodecFile()
        try {
            TfliteSignatureInputs.inputsFor(file, "prefill")
            fail("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // The graph carries only the 'decode' signature.
        } finally {
            file.delete()
        }
    }

    private fun assemblePinnedCodecFile(): File {
        val head = readResource("/codec_signature_head.bin")
        val tail = readResource("/codec_signature_tail.bin")
        val file = File.createTempFile("codec-signature", ".tflite")
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(CODEC_FILE_BYTES)
            out.seek(0)
            out.write(head)
            out.seek(CODEC_FILE_BYTES - tail.size)
            out.write(tail)
        }
        return file
    }

    private fun readResource(name: String): ByteArray =
        javaClass.getResourceAsStream(name)?.readBytes()
            ?: error("missing test resource $name")

    private companion object {
        const val CODEC_FILE_BYTES = 152_370_912L
    }
}
