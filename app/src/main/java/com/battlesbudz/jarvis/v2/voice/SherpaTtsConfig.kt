package com.battlesbudz.jarvis.v2.voice

import com.k2fsa.sherpa.onnx.*

internal fun sherpaTtsConfig(engine: TtsEngine, directory: String, threads: Int, piperWholePassage: Boolean = false): OfflineTtsConfig =
    OfflineTtsConfig(model = OfflineTtsModelConfig(numThreads = threads, debug = false, provider = "cpu",
        vits = OfflineTtsVitsModelConfig(model = "$directory/${engine.modelFile}",
            tokens = "$directory/tokens.txt", dataDir = "$directory/espeak-ng-data")),
        maxNumSentences = if (piperWholePassage) 0 else 1, silenceScale = 1f)
