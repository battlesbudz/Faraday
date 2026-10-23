package com.battlesbudz.jarvis.v2.ai

import com.battlesbudz.jarvis.v2.actions.NativeActionDecoder
import com.battlesbudz.jarvis.v2.voice.AudioToolGuard
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolCallParseFailureTest {
    /**
     * The exact malformed call from Battles's build-1563 Voice Call
     * diagnostics (call 5fb2e42c, turn 6b2df2ba): the argument value
     * "Facebook" is duplicated inside the call, and LiteRT-LM rejected the
     * whole generation with Status Code 3.
     */
    private val dumpCall = "call:open_app{app:<|\"|>Facebook<|\"|>Facebook<|\"|>}"
    private val dumpError = RuntimeException(
        "Failed to parse tool calls from code block: $dumpCall")

    @Test fun sdkParseRejectionIsClassifiedAsToolCallParseFailure() {
        assertTrue(ToolCallParseFailure.isFailure(dumpError))
    }

    @Test fun parseFailureIsFoundThroughTheCauseChain() {
        val wrapped = IllegalStateException("Voice turn failed", dumpError)
        assertTrue(ToolCallParseFailure.isFailure(wrapped))
    }

    @Test fun modelLoadFailureIsNotAToolCallParseFailure() {
        assertFalse(ToolCallParseFailure.isFailure(
            RuntimeException("Failed to load the model file: missing weights")))
        assertFalse(ToolCallParseFailure.isFailure(null))
    }

    @Test fun rawCallTextIsExtractedFromTheSdkMessage() {
        assertEquals(dumpCall, ToolCallParseFailure.rawCallText(dumpError))
        assertNull(ToolCallParseFailure.rawCallText(RuntimeException("unrelated")))
    }

    @Test fun dumpMalformationSalvagesToTheCallTheModelIntended() {
        val salvaged = MalformedToolCallSalvage.salvage(dumpError)
        assertNotNull(salvaged)
        assertEquals("open_app", salvaged!!.name)
        assertEquals("Facebook", JSONObject(salvaged.arguments).getString("app"))
        // The salvaged call must still pass the strict schema decode and the
        // direct-audio tool guard before it may run.
        val request = NativeActionDecoder.decodeStrict(salvaged)
        assertNotNull(request)
        assertTrue(AudioToolGuard.allows(request!!))
    }

    @Test fun wellFormedCallParsesWithoutSalvageChanges() {
        val parsed = MalformedToolCallSalvage.parse("call:open_app{app:<|\"|>Facebook<|\"|>}")
        assertNotNull(parsed)
        assertEquals("Facebook", JSONObject(parsed!!.arguments).getString("app"))
    }

    @Test fun bareLiteralArgumentsParse() {
        val parsed = MalformedToolCallSalvage.parse("call:set_volume{level:50}")
        assertNotNull(parsed)
        assertEquals("50", JSONObject(parsed!!.arguments).getString("level"))
    }

    @Test fun aDifferentSecondSegmentIsNotSalvageable() {
        assertNull(MalformedToolCallSalvage.parse(
            "call:open_app{app:<|\"|>Facebook<|\"|>Twitter<|\"|>}"))
    }

    @Test fun aTripleDuplicationIsNotSalvageable() {
        assertNull(MalformedToolCallSalvage.parse(
            "call:open_app{app:<|\"|>Facebook<|\"|>Facebook<|\"|>Facebook<|\"|>}"))
    }

    @Test fun truncatedOrTrailingJunkIsNotSalvageable() {
        assertNull(MalformedToolCallSalvage.parse("call:open_app{app:<|\"|>Facebook"))
        assertNull(MalformedToolCallSalvage.parse(
            "call:open_app{app:<|\"|>Facebook<|\"|>Facebook<|\"|>} extra"))
        assertNull(MalformedToolCallSalvage.parse("open_app{app:<|\"|>Facebook<|\"|>}"))
        assertNull(MalformedToolCallSalvage.salvage(RuntimeException("no parse marker here")))
    }
}
