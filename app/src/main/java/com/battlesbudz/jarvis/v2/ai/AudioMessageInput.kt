package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents

/** Gemma 4 expects instruction text before audio within the same native user message. */
internal fun audioMessageContents(prompt: String, audioBytes: ByteArray): Contents {
    require(audioBytes.isNotEmpty()) { "The voice recording is empty." }
    return Contents.of(Content.Text(prompt), Content.AudioBytes(audioBytes))
}
