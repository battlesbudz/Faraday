package com.battlesbudz.jarvis.v2.voice

import java.util.Locale
import kotlinx.coroutines.CancellationException

/** Checks the first meaningful speech clause and complete sentences before publication. */
class VoiceRepetitionGuard(user: String, previousReply: String?, private val emit: (String) -> Unit) {
    private val references = (listOf(user, previousReply.orEmpty()) +
        (user + "\n" + previousReply.orEmpty()).split(Regex("[.!?\\n]+")))
        .filter { it.isNotBlank() }.toMutableList()
    private val pending = StringBuilder()
    private val accepted = StringBuilder()
    // Retain the complete first sentence for duplicate/policy checks and repair accounting even
    // when its checked opening clause has already reached Piper. Never republish that prefix.
    private var publishedPrefixLength = 0
    private var publishedPrefix: String? = null
    private var openingChecked = false
    private var pendingRejection: Rejection? = null
    private enum class Rejection { POLICY, DUPLICATE }
    private var received = false
    private val runaway = RunawayRepetition()
    /** Native streaming must stop on this specific cancellation, without swallowing user Stop. */
    class RunawayLoop : CancellationException("voice_runaway_repetition")
    var loopDetected: Boolean = false
        private set
    /** Begins the one controlled recovery while retaining the valid already-delivered opening. */
    fun beginRepair() { loopDetected = false; discardPending() }
    /** Optional evidence/knowledge-gap policy, applied before every publication. */
    var isPublishable: (String) -> Boolean = { true }
    private var policyRejected = false
    private var codeFence = false
    var preserveFormatting: Boolean = false
    var suppressedSentences = 0
        private set
    var acceptedSentences = 0
        private set
    fun discardPending() {
        // A cancelled or rejected tail must not make its already-published prefix repeatable.
        rememberPublishedPrefix()
        pending.clear()
        publishedPrefixLength = 0
        publishedPrefix = null
        openingChecked = false
        pendingRejection = null
        runaway.reset()
    }
    val text: String get() = accepted.toString()
    val needsRepair: Boolean get() = loopDetected || ((suppressedSentences > 0 || policyRejected) && text.isBlank())

    fun accept(chunk: String) {
        if (chunk.isEmpty()) return
        received = true
        if (loopDetected) throw RunawayLoop()
        // Consume callbacks incrementally: a huge native chunk cannot flush a broken suffix first.
        for (character in chunk) {
            pending.append(character)
            // Formatting-preserving text can contain intentionally repetitive code or data.
            if (!preserveFormatting && runaway.accept(character)) {
                loopDetected = true
                discardPending()
                throw RunawayLoop()
            }
            drainSentences()
            if (pending.length > 4096) { publishPending(pending.toString()); discardPending() }
        }
    }
    private fun drainSentences() {
        while (true) {
            val end = SpeechTextBoundaries.sentenceEnds(pending).firstOrNull()
            if (end == null) {
                if (!preserveFormatting && accepted.isEmpty() && !openingChecked) {
                    val clauseEnd = SpeechTextBoundaries.openingClauseEnd(pending) ?: return
                    openingChecked = true
                    val phrase = pending.substring(0, clauseEnd).trim()
                    pendingRejection = rejection(phrase)
                    if (pendingRejection == null) {
                        accepted.append(phrase)
                        publishedPrefixLength = clauseEnd
                        publishedPrefix = phrase
                        emit(phrase)
                    }
                }
                return
            }
            val sentence = pending.substring(0, end)
            pending.delete(0, end)
            publishPending(sentence)
            publishedPrefixLength = 0
            publishedPrefix = null
            openingChecked = false
            pendingRejection = null
            runaway.reset()
            // The suffix is at most the lookahead character resolving a sentence boundary.
            pending.forEach { runaway.accept(it) }
        }
    }

    private fun rejection(phrase: String): Rejection? = when {
        !isPublishable(phrase) -> Rejection.POLICY
        references.any { duplicates(phrase, it) } -> Rejection.DUPLICATE
        else -> null
    }

    private fun recordRejection(reason: Rejection) {
        if (reason == Rejection.POLICY) policyRejected = true else suppressedSentences++
    }

    private fun rememberPublishedPrefix() {
        publishedPrefix?.let { if (it !in references) references += it }
    }

    private fun publishPending(sentence: String) {
        val rejected = pendingRejection
        if (rejected != null) { recordRejection(rejected); return }
        try { publish(sentence, publishedPrefixLength) }
        finally { rememberPublishedPrefix() }
    }

    fun finish(finalText: String? = null): String {
        if (loopDetected) { discardPending(); return text }
        if (!received && finalText != null) accept(finalText)
        if (!preserveFormatting && runaway.accept(' ')) {
            loopDetected = true; discardPending(); throw RunawayLoop()
        }
        if (pending.isNotBlank()) publishPending(pending.toString())
        discardPending()
        // If every streamed draft sentence failed policy, a final safe fallback may still be spoken.
        if (accepted.isEmpty() && policyRejected && finalText != null && isPublishable(finalText)) {
            publish(finalText)
        }
        return text
    }
    private fun publish(candidate: String, prefixLength: Int = 0) {
        val phrase = candidate.trim()
        if (phrase.isBlank()) {
            if (preserveFormatting && accepted.isNotEmpty()) { accepted.append(candidate); emit(candidate) }
            return
        }
        val fence = preserveFormatting && phrase.startsWith("```")
        val literal = preserveFormatting && (codeFence || fence)
        if (!literal) rejection(phrase)?.let { recordRejection(it); return }
        val remainder = candidate.substring(prefixLength).trim()
        val output = if (preserveFormatting) candidate else if (remainder.isEmpty()) "" else
            (if (accepted.isEmpty()) "" else " ") + remainder
        accepted.append(output)
        acceptedSentences++
        if (fence) codeFence = !codeFence
        if (!literal) references += phrase
        if (output.isNotEmpty()) emit(output)
    }

    companion object {
        /** Expose an existing checked clause/sentence boundary without inventing punctuation. */
        fun speechReady(phrase: String): String = phrase.trim() + " "

        private fun words(text: String): List<String> = Regex("[\\p{L}\\p{N}]+")
            .findAll(text.lowercase(Locale.ROOT)).map { it.value }.toList()
        private fun grams(words: List<String>, size: Int) = words.windowed(size).map { it.joinToString(" ") }.toSet()
        fun duplicates(candidate: String, reference: String): Boolean {
            val a = words(candidate)
            val b = words(reference)
            if (a.isEmpty() || b.isEmpty()) return false
            if (a == b) return true
            val an = a.joinToString(" ")
            val bn = b.joinToString(" ")
            if (a.size >= 3 && (" $bn ").contains(" $an ")) return true
            if (b.size >= 6 && (" $an ").contains(" $bn ")) return true
            if (a.size < 8 || b.size < 8) return false
            if (grams(a, 12).intersect(grams(b, 12)).isNotEmpty()) return true
            val candidateGrams = grams(a, 4)
            return candidateGrams.intersect(grams(b, 4)).size.toDouble() / candidateGrams.size >= .60
        }
    }
}
