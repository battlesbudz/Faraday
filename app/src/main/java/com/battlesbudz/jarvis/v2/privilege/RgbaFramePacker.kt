package com.battlesbudz.jarvis.v2.privilege

import java.nio.ByteBuffer

/** Packs ImageReader RGBA planes into the bounded, tightly packed frame wire format. */
internal object RgbaFramePacker {
    data class Packed(val width: Int, val height: Int, val rgba: ByteArray, val capturedAtMs: Long)

    fun pack(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        capturedAtMs: Long,
    ): Packed {
        require(width > 0 && height > 0 && rowStride > 0 && pixelStride >= 4)
        val rowBytes = (width - 1L) * pixelStride + 4L
        require(rowStride.toLong() >= rowBytes) { "Row stride cannot hold the declared pixels." }
        val source = buffer.duplicate()
        val base = source.position()
        val lastByteExclusive = base.toLong() + (height - 1L) * rowStride + rowBytes
        require(lastByteExclusive <= source.limit()) { "RGBA plane buffer is shorter than its dimensions and strides." }

        val edge = maxOf(width, height)
        val scale = if (edge > MAX_EDGE) MAX_EDGE.toDouble() / edge else 1.0
        val outWidth = if (edge > MAX_EDGE) maxOf(1, (width * scale).toInt()) else width
        val outHeight = if (edge > MAX_EDGE) maxOf(1, (height * scale).toInt()) else height
        val outputSize = outWidth.toLong() * outHeight * 4L
        require(outputSize <= Int.MAX_VALUE)
        val rgba = ByteArray(outputSize.toInt())
        for (y in 0 until outHeight) for (x in 0 until outWidth) {
            val sx = minOf(width - 1, (x / scale).toInt())
            val sy = minOf(height - 1, (y / scale).toInt())
            val src = base + sy * rowStride + sx * pixelStride
            val dst = (y * outWidth + x) * 4
            rgba[dst] = source.get(src)
            rgba[dst + 1] = source.get(src + 1)
            rgba[dst + 2] = source.get(src + 2)
            rgba[dst + 3] = source.get(src + 3)
        }
        return Packed(outWidth, outHeight, rgba, capturedAtMs)
    }

    const val MAX_EDGE = 360
}

/** Per-display newest-frame cache. A failed refresh preserves a previously acquired frame. */
internal class HelperFrameCache(private val clockMs: () -> Long) {
    private data class Entry(val packed: RgbaFramePacker.Packed?, val refreshedAtMs: Long)
    private val entries = mutableMapOf<Int, Entry>()

    @Synchronized fun frame(displayId: Int, acquire: () -> RgbaFramePacker.Packed?): RgbaFramePacker.Packed? {
        val now = clockMs()
        val cached = entries[displayId]
        if (cached == null || now - cached.refreshedAtMs >= REFRESH_INTERVAL_MS) {
            val refreshed = acquire()
            entries[displayId] = Entry(refreshed ?: cached?.packed, now)
        }
        return entries[displayId]?.packed
    }

    @Synchronized fun invalidate(displayId: Int) { entries.remove(displayId) }
    @Synchronized fun clear() { entries.clear() }

    companion object { const val REFRESH_INTERVAL_MS = 100L }
}
