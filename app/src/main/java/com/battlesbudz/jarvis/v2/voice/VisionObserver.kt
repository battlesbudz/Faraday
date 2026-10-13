package com.battlesbudz.jarvis.v2.voice

/**
 * Narrow hook for vision consumers. Slice 2 (native ONNX Runtime inference)
 * plugs in here; slice 1 only captures frames and fans them out.
 *
 * Implementations must return quickly: dispatch happens on the camera
 * analysis thread. Heavy work belongs on the observer's own thread.
 */
interface VisionObserver {
    /** JPEG bytes of a downscaled frame; never null, never empty. */
    fun onFrame(jpegBytes: ByteArray, timestampMs: Long)
}

/** A single cached frame. ByteArray equality is by content. */
data class VisionFrame(val jpegBytes: ByteArray, val timestampMs: Long) {
    override fun equals(other: Any?): Boolean =
        other is VisionFrame &&
            timestampMs == other.timestampMs &&
            jpegBytes.contentEquals(other.jpegBytes)

    override fun hashCode(): Int = 31 * timestampMs.hashCode() + jpegBytes.contentHashCode()
}
