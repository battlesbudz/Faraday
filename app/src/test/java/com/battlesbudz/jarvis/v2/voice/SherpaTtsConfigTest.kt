package com.battlesbudz.jarvis.v2.voice

import org.junit.Assert.*
import org.junit.Test

class SherpaTtsConfigTest {
    @Test fun northernPiperUsesVitsAndPersistsItsOwnIdentity() {
        val engine = TtsEngine.PIPER_NORTHERN
        val config = sherpaTtsConfig(engine, "/models/northern", 2)
        assertEquals(engine, TtsEngine.fromId(engine.id))
        assertEquals("/models/northern/en_GB-northern_english_male-medium.onnx", config.model.vits.model)
        assertEquals("/models/northern/tokens.txt", config.model.vits.tokens)
        assertEquals("/models/northern/espeak-ng-data", config.model.vits.dataDir)
        assertTrue(config.model.kokoro.model.isEmpty())
        assertTrue(config.model.pocket.lmMain.isEmpty())
        assertEquals(engine.modelBytes, NorthernPiperSpec.files[engine.modelFile])
        assertTrue(NorthernPiperSpec.files.containsKey("MODEL_CARD"))
        assertTrue(NorthernPiperSpec.files.keys.any { it.startsWith("espeak-ng-data/") })
    }

    @Test fun piperPassagesDisableNativeSentenceSplittingAndKeepNaturalPauses() {
        val passage = sherpaTtsConfig(TtsEngine.PIPER_NORTHERN, "/models/northern", 4, piperWholePassage = true)
        assertEquals(0, passage.maxNumSentences)
        assertEquals(1f, passage.silenceScale, 0f)
        val legacy = sherpaTtsConfig(TtsEngine.PIPER_NORTHERN, "/models/northern", 4)
        assertEquals(1, legacy.maxNumSentences)
        assertEquals(1f, legacy.silenceScale, 0f)
    }



}
