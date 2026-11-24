package com.battlesbudz.jarvis.v2.privilege

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class RgbaFramePackerTest {
    @Test fun stripsPaddedRowsWithoutLeakingPadding() {
        val bytes = byteArrayOf(
            1, 2, 3, 4, 5, 6, 7, 8, 99, 99, 99, 99,
            9, 10, 11, 12, 13, 14, 15, 16, 88, 88, 88, 88,
        )
        val packed = RgbaFramePacker.pack(ByteBuffer.wrap(bytes), 2, 2, 12, 4, 42)
        assertEquals(2, packed.width)
        assertEquals(2, packed.height)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), packed.rgba)
        assertEquals(42L, packed.capturedAtMs)
    }

    @Test fun handlesInterleavedPixelsWithStrideGreaterThanFour() {
        val bytes = byteArrayOf(1, 2, 3, 4, 70, 71, 72, 73, 5, 6, 7, 8, 80, 81, 82, 83)
        val packed = RgbaFramePacker.pack(ByteBuffer.wrap(bytes), 2, 1, 16, 8, 1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), packed.rgba)
    }

    @Test fun downsamplesToLongEdge360UsingNearestNeighborSamples() {
        val width = 800
        val height = 400
        val source = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val offset = (y * width + x) * 4
            source[offset] = x.toByte()
            source[offset + 1] = y.toByte()
            source[offset + 2] = 3
            source[offset + 3] = 4
        }
        val packed = RgbaFramePacker.pack(ByteBuffer.wrap(source), width, height, width * 4, 4, 9)
        assertEquals(360, packed.width)
        assertEquals(180, packed.height)
        val scale = 360.0 / 800
        for ((x, y) in listOf(0 to 0, 1 to 1, 179 to 89, 359 to 179)) {
            val sx = minOf(width - 1, (x / scale).toInt())
            val sy = minOf(height - 1, (y / scale).toInt())
            val offset = (y * packed.width + x) * 4
            assertEquals(sx.toByte(), packed.rgba[offset])
            assertEquals(sy.toByte(), packed.rgba[offset + 1])
        }
    }

    @Test fun smallFrameKeepsNativeDimensionsAndPixels() {
        val input = byteArrayOf(2, 3, 4, 5, 6, 7, 8, 9)
        val packed = RgbaFramePacker.pack(ByteBuffer.wrap(input), 2, 1, 8, 4, 2)
        assertEquals(2, packed.width)
        assertEquals(1, packed.height)
        assertArrayEquals(input, packed.rgba)
    }

    @Test fun rejectsInvalidDimensionsAndStride() {
        assertThrows(IllegalArgumentException::class.java) {
            RgbaFramePacker.pack(ByteBuffer.allocate(4), 0, 1, 4, 4, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaFramePacker.pack(ByteBuffer.allocate(4), 1, -1, 4, 4, 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RgbaFramePacker.pack(ByteBuffer.allocate(4), 1, 1, 4, 3, 0)
        }
    }
}
