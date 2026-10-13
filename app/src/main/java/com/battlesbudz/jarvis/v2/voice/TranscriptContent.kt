package com.battlesbudz.jarvis.v2.voice

/** Strip only explicit, known ASR sound captions. Plain words and unknown brackets remain speech. */
internal object TranscriptContent {
    private val captions = Regex("\\([^()]*\\)|\\[[^\\[\\]]*\\]|[♪♫]+")
    private val sounds = setOf("crying", "sobbing", "laughing", "laughter", "chuckling", "sigh", "sighing", "sighs",
        "breathing", "heavy breathing", "applause", "clapping", "music", "dramatic music", "background music",
        "instrumental music", "tense music", "eerie music", "singing", "noise", "background noise", "static",
        "buzzer", "buzzing", "buzz", "beep", "beeping", "ringing", "silence", "inaudible", "unintelligible", "no speech", "blank audio", "cough", "coughing", "coughs",
        "sneeze", "sneezing", "sneezes", "yelling", "screaming", "wind", "wind noise", "traffic", "car noise", "engine noise")
    fun speech(text: String): String = captions.replace(text) { match ->
        val label = match.value.trim('(', ')', '[', ']').lowercase().replace('_', ' ').trim()
        if (label in sounds || match.value.all { it == '♪' || it == '♫' }) " " else match.value
    }.replace(Regex("\\s+"), " ").trim()
    fun isSoundOnly(text: String): Boolean = text.isNotBlank() && speech(text).none { it.isLetterOrDigit() } &&
        captions.containsMatchIn(text)
}
