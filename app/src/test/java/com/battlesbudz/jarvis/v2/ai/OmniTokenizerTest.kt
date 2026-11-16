package com.battlesbudz.jarvis.v2.ai

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class OmniTokenizerTest {
    @Test fun byteBpeMergesByRankAndRoundTripsUtf8() {
        val tokenizer = OmniTokenizer.load(StringReader("""
            {"model":{"vocab":{"h":0,"i":1,"hi":2,"Ġ":3,"Ġhi":4,"Ã":5,"©":6},"merges":["Ġ h","h i","Ġh i"]},"added_tokens":[]}
        """.trimIndent()))

        assertArrayEquals(intArrayOf(2), tokenizer.encode("hi"))
        assertArrayEquals(intArrayOf(4), tokenizer.encode(" hi"))
        assertEquals("hi", tokenizer.decode(intArrayOf(2)))
    }

    @Test fun specialTokensAreAtomicAndDecodeLiterally() {
        val tokenizer = OmniTokenizer.load(StringReader("""
            {"model":{"vocab":{"<|im_start|>":10,"h":0,"i":1,"hi":2},"merges":["h i"]},"added_tokens":[{"id":10,"content":"<|im_start|>","special":true}]}
        """.trimIndent()))

        assertArrayEquals(intArrayOf(10, 2), tokenizer.encode("<|im_start|>hi"))
        assertEquals("<|im_start|>hi", tokenizer.decode(intArrayOf(10, 2)))
    }

    @Test fun typedPromptUsesTextOnlyLfmMessageFraming() {
        assertEquals(
            "<|startoftext|><|im_start|>system\nYou are Jarvis, a helpful assistant. Respond with text." +
                "<|im_end|>\n<|im_start|>user\nHello<|im_end|>\n<|im_start|>assistant\n<think></think>",
            OmniChatPrompt.userTurn("Hello", startsConversation = true),
        )
        assertEquals("<|im_end|>\n<|im_start|>user\nAgain<|im_end|>\n<|im_start|>assistant\n<think></think>",
            OmniChatPrompt.userTurn("Again", startsConversation = false))
    }

    @Test fun fakeEngineResetStartsNextConversationFromCleanSeam() {
        val engine = FakeOmniEngine { "answer:$it" }
        assertEquals("answer:first", engine.generateText("first"))
        engine.resetConversation()
        assertEquals("answer:second", engine.generateText("second"))
        assertEquals(1, engine.resets)
    }

    @Test fun prefillSignaturesCoverPromptExactlyLargestFirst() {
        assertEquals(listOf(1024, 256, 64, 16, 4, 1),
            OmniLmRunner.prefillPlan(1365, intArrayOf(1024, 256, 64, 16, 4, 1)))
        assertEquals(emptyList<Int>(), OmniLmRunner.prefillPlan(0, intArrayOf(1024, 256, 64, 16, 4, 1)))
    }
}
