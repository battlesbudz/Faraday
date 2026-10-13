package com.battlesbudz.jarvis.v2.voice

import kotlinx.coroutines.flow.Flow

interface VoiceOutput {
    suspend fun speak(chunks: Flow<String>, onChunkStarted: (String) -> Unit = {})
    fun stopSpeaking()
}

