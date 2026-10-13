package com.battlesbudz.jarvis.v2.voice

import java.util.Locale

/** Bounded incremental detector for exact, adjacent word loops inside unfinished prose. */
internal class RunawayRepetition {
    private val words = ArrayDeque<String>()
    private val word = StringBuilder()
    private var oversizedWord = false

    fun reset() { words.clear(); word.clear(); oversizedWord = false }

    fun accept(character: Char): Boolean {
        if (character.isLetterOrDigit()) {
            if (word.length < 128) word.append(character) else oversizedWord = true
            return false
        }
        if (word.isEmpty()) return false
        if (oversizedWord) { reset(); return false }
        words.addLast(word.toString().lowercase(Locale.ROOT))
        word.clear()
        if (words.size > 48) words.removeFirst()
        val tail = words.toList()
        for (size in 1..12) {
            val repeats = if (size == 1) 8 else 4
            val required = size * repeats
            if (tail.size < required) continue
            val phrase = tail.takeLast(size)
            // Numeric tables and mathematical expressions must not look like speech loops.
            if (phrase.none { token -> token.length >= 3 && token.any(Char::isLetter) }) continue
            val start = tail.size - required
            if ((0 until required).all { tail[start + it] == phrase[it % size] }) return true
        }
        return false
    }
}
