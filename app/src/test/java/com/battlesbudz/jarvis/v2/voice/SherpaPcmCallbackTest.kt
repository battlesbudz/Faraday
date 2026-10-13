package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SherpaPcmCallbackTest {
    @Test fun exposesExactBoxedSignatureRequiredByNativeLookup() {
        val method = SherpaPcmCallback::class.java.getMethod("invoke", FloatArray::class.java)
        assertEquals(Integer::class.java, method.returnType)
        val samples = floatArrayOf(0.2f, -0.3f)
        var received: FloatArray? = null
        val callback = SherpaPcmCallback { received = it; 1 }
        assertEquals(1, method.invoke(callback, samples))
        assertSame(samples, received)
        assertNull(callback.failure)
    }
    @Test fun cancellationAndFailuresStopNativeWithoutThrowingAcrossJni() {
        val problem = java.util.concurrent.CancellationException("cancelled")
        var calls = 0
        val callback = SherpaPcmCallback { calls++; throw problem }
        assertEquals(0, callback.invoke(floatArrayOf(0f)))
        assertEquals(0, callback.invoke(floatArrayOf(0f)))
        assertSame(problem, callback.failure)
        assertEquals(1, calls)
    }
    @Test fun stopSignalPassesThroughWithoutAnError() {
        val callback = SherpaPcmCallback { 0 }
        assertEquals(0, callback.invoke(floatArrayOf(0f)))
        assertNull(callback.failure)
    }
}
