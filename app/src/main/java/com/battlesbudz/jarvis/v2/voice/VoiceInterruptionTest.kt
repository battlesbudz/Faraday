package com.battlesbudz.jarvis.v2.voice

/** Explicit local test command; its answer uses the ordinary call playback and interruption path. */
object VoiceInterruptionTest {
    fun requested(text: String): Boolean = text.trim().trimEnd('.', '!', '?').lowercase(java.util.Locale.ROOT) in
        setOf("start interruption test", "start the interruption test")

    val passage = """
        The lantern glowed beside the river. Mira followed a narrow path through the trees, carrying a map her grandfather had drawn.
        Across the water, an old clock tower stood above a village of blue rooftops. Its bell had been silent for many years.
        She found a wooden bridge and crossed slowly, listening to the water beneath her feet. A small brown dog joined her on the other side.
        Together they walked toward the tower, where someone had left a basket of apples by the door. Mira picked up an apple and noticed a tiny silver key underneath it.
        Somewhere above them, the clock began to tick again.
    """.trimIndent().replace('\n', ' ')
}
