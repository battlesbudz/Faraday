package com.battlesbudz.jarvis.v2.display

import android.os.Bundle

data class FrameParcel(val width: Int, val height: Int, val rgba: ByteArray, val capturedAtMs: Long)

object OperatedFrameCodec {
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_RGBA = "rgba"
    const val KEY_CAPTURED_AT_MS = "capturedAtMs"

    fun toParcel(frame: DisplayFrameMailbox.Frame) =
        FrameParcel(frame.width, frame.height, frame.rgba, frame.capturedAtMs)

    fun fromParcel(displayId: Int, parcel: FrameParcel): DisplayFrameMailbox.Frame? {
        if (displayId < 0 || parcel.width <= 0 || parcel.height <= 0) return null
        val expectedBytes = parcel.width.toLong() * parcel.height * 4L
        if (expectedBytes <= 0 || expectedBytes > Int.MAX_VALUE || expectedBytes != parcel.rgba.size.toLong()) return null
        return DisplayFrameMailbox.Frame(displayId, parcel.width, parcel.height, parcel.rgba, parcel.capturedAtMs)
    }
}

fun FrameParcel.toBundle(): Bundle = Bundle().apply {
    putInt(OperatedFrameCodec.KEY_WIDTH, width)
    putInt(OperatedFrameCodec.KEY_HEIGHT, height)
    putByteArray(OperatedFrameCodec.KEY_RGBA, rgba)
    putLong(OperatedFrameCodec.KEY_CAPTURED_AT_MS, capturedAtMs)
}

fun Bundle.toFrameParcel(): FrameParcel? {
    if (!containsKey(OperatedFrameCodec.KEY_WIDTH) || !containsKey(OperatedFrameCodec.KEY_HEIGHT) ||
        !containsKey(OperatedFrameCodec.KEY_RGBA) || !containsKey(OperatedFrameCodec.KEY_CAPTURED_AT_MS)) return null
    val rgba = getByteArray(OperatedFrameCodec.KEY_RGBA) ?: return null
    return FrameParcel(getInt(OperatedFrameCodec.KEY_WIDTH), getInt(OperatedFrameCodec.KEY_HEIGHT), rgba,
        getLong(OperatedFrameCodec.KEY_CAPTURED_AT_MS))
}
