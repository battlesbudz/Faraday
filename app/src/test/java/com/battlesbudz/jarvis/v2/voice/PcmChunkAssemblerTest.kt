package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class PcmChunkAssemblerTest {
    @Test fun slowAsrStartupWithTinyReadsRetainsEveryByteWithinQueueBudget() {
        val source = ByteArray(16_000 * 2 * 3) { (it % 251).toByte() }
        val chunks = mutableListOf<ByteArray>()
        val assembler = PcmChunkAssembler(3200)
        // 600 hardware reads used to overflow the 64-entry channel in under a second.
        source.asList().chunked(160).forEach { assembler.accept(it.toByteArray(), it.size, chunks::add) }
        assertEquals(30, chunks.size)
        assertTrue(chunks.size < 64)
        assertArrayEquals(source, chunks.flatMap { it.asList() }.toByteArray())
    }
    @Test fun unevenReadBoundariesPreserveSampleOrder() {
        val assembler = PcmChunkAssembler(8)
        val chunks = mutableListOf<ByteArray>()
        assembler.accept(byteArrayOf(1, 2, 3), 3, chunks::add)
        assertTrue(chunks.isEmpty())
        assembler.accept(byteArrayOf(4, 5, 6, 7, 8, 9, 10), 7, chunks::add)
        assembler.accept(byteArrayOf(11, 12, 13, 14, 15, 16), 6, chunks::add)
        assertArrayEquals((1..16).map { it.toByte() }.toByteArray(), chunks.flatMap { it.asList() }.toByteArray())
    }
}
