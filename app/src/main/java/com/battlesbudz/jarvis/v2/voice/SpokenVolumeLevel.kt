package com.battlesbudz.jarvis.v2.voice

/** Speech recognition can emit number words; retain the last requested value after a correction. */
object SpokenVolumeLevel {
    private val small = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val tens = listOf("twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val expression = Regex("\\b(?:[0-9]{1,5}|(?:one )?hundred|(?:" + tens.joinToString("|") + ")(?:[ -](?:" + small.drop(1).take(9).joinToString("|") + "))?|" + small.joinToString("|") + ")\\b")
    fun fromTranscript(text: String): Int? {
        val normalized = text.lowercase()
        val position = normalized.indexOf("volume")
        if (position < 0) return null
        val value = expression.findAll(normalized.substring(position + 6)).lastOrNull()?.value ?: return null
        value.toIntOrNull()?.let { return it }
        if (value.endsWith("hundred")) return 100
        val words = value.split(' ', '-')
        val ten = tens.indexOf(words[0])
        return if (ten >= 0) (ten + 2) * 10 + (words.getOrNull(1)?.let { small.indexOf(it) } ?: 0)
        else small.indexOf(value).takeIf { it >= 0 }
    }
}
