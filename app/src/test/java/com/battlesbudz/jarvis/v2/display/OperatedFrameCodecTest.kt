package com.battlesbudz.jarvis.v2.display

import org.junit.Assert.*
import org.junit.Test

class OperatedFrameCodecTest {
    @Test fun parcelRoundTripPreservesFrame() {
        val frame = DisplayFrameMailbox.Frame(7, 2, 1, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 123)
        assertEquals(frame, OperatedFrameCodec.fromParcel(frame.displayId, OperatedFrameCodec.toParcel(frame)))
    }

    @Test fun rejectsWrongByteCountAndNonPositiveDimensions() {
        assertNull(OperatedFrameCodec.fromParcel(7, FrameParcel(1, 1, byteArrayOf(1, 2, 3), 1)))
        assertNull(OperatedFrameCodec.fromParcel(7, FrameParcel(0, 1, byteArrayOf(), 1)))
        assertNull(OperatedFrameCodec.fromParcel(7, FrameParcel(1, -1, byteArrayOf(), 1)))
    }
}
