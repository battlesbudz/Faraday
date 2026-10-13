package com.battlesbudz.jarvis.v2.chat

import com.battlesbudz.jarvis.v2.ai.ModelCatalog
import org.junit.Assert.*
import org.junit.Test

class AttachmentPolicyTest {
    @Test fun convertedTextOnlyBundlesRejectImagesButVisionAndAudioRemainAvailable() {
        assertFalse(AttachmentPolicy.accepts(ModelCatalog.find("Qwen3.5-4B")!!, AttachmentKind.IMAGE))
        assertTrue(AttachmentPolicy.accepts(ModelCatalog.find("FastVLM-0.5B")!!, AttachmentKind.IMAGE))
        assertFalse(AttachmentPolicy.accepts(ModelCatalog.find("Qwen2-VL-2B")!!, AttachmentKind.AUDIO))
        assertTrue(AttachmentPolicy.accepts(ModelCatalog.gemma4E2b, AttachmentKind.AUDIO))
        assertTrue(AttachmentPolicy.accepts(ModelCatalog.gemma4E4b, AttachmentKind.IMAGE))
    }
    @Test fun readsStopBeforeOversizedContentIsAllocated() {
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPolicy.readBounded(ByteArray(AttachmentPolicy.MAX_BYTES + 1).inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.readBounded(byteArrayOf().inputStream()) }
        assertArrayEquals(byteArrayOf(1, 2, 3), AttachmentPolicy.readBounded(byteArrayOf(1, 2, 3).inputStream()))
    }
    @Test fun invalidAndOverlongAudioCannotReachNativeInference() {
        val wav = com.battlesbudz.jarvis.v2.voice.WavEncoder.pcm16Mono(ByteArray(32000), 16000)
        AttachmentPolicy.validateAudio(wav)
        assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.validateAudio(byteArrayOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { AttachmentPolicy.validateAudio(wav.copyOf(100)) }
        assertThrows(IllegalArgumentException::class.java) {
            AttachmentPolicy.validateAudio(com.battlesbudz.jarvis.v2.voice.WavEncoder.pcm16Mono(ByteArray(32000 * 31), 16000))
        }
    }
}
