package com.battlesbudz.jarvis.v2.ai

/** Literal conversation resolution. This never guesses a replacement for ASR words. */
internal object TurnQuestionPolicy {
    data class Context(val question: String? = null, val subject: String? = null)
    private val correction = Regex("(?i)^(?:no[,!]?\\s+)?i (?:said|meant|(?:was|am) asking(?: you)?)\\s*[, :]?\\s*(.+?)[.!?]*$")
    private val knowledge = Regex("(?i)^(?:no[,!]?\\s+)?(?:who|what|where|when|how|why|which)\\b")
    private val referential = Regex("(?i)\\b(?:one|ones|it|that|this|him|her|his|their)\\b")
    private val modifiers = Regex("(?i)^(?:(?:the|a|an|first|original|earliest)\\s+)+")
    private val predicates = "founded|opened|established|built|born|located|invented|discovered|created|start(?:ed)?|begin|began|come from|from"

    fun subject(question: String): String? {
        val plain = question.substringBefore("\nConversation domain:").trim()
        correction.matchEntire(plain)?.groupValues?.get(1)?.let { payload ->
            if (!knowledge.containsMatchIn(payload)) return cleanSubject(payload)
        }
        val direct = Regex("(?i)\\b(?:tell me about|who is|who was|who|what is|what was|information about)\\s+(.+?)(?:[?.!]\\s*$|$)").find(plain)
        direct?.groupValues?.get(1)?.let { cleanSubject(it)?.let { value -> return value } }
        val historical = Regex("(?i)\\b(?:where|when|how|why)\\s+(?:was|were|is|are|did|does|would)\\s+(.+?)\\s+(?:$predicates)\\b").find(plain)
        historical?.groupValues?.get(1)?.let { cleanSubject(it)?.let { value -> return value } }
        // The same literal entity can appear inside a noisy prefix. Capitalisation
        // is an optional signal, never a requirement for ordinary questions.
        val named = Regex("\\b[A-Z][a-z]{2,}(?:\\s+(?:[A-Z]\\.?|[A-Z][a-z]{2,})){1,5}\\b").findAll(plain).map { it.value.trim() }
            .filterNot { it.lowercase() in setOf("can you", "what is", "what was", "who was", "tell me", "how did", "what about", "how about", "use wikipedia") }.toList()
        named.lastOrNull()?.let { return it }
        return Regex("\\b[A-Z]{2,}(?:\\s+[A-Z]{2,})*\\b").find(plain)?.value
    }

    private fun cleanSubject(raw: String): String? = raw.trim().trimEnd('.', '?', '!').replace(modifiers, "").trim().takeIf {
        it.isNotBlank() && it.length <= 120 && it.split(Regex("\\s+")).size <= 12 &&
            !referential.containsMatchIn(it) && !Regex("(?i)^(?:not |anything |nothing |no |wrong\\b)").containsMatchIn(it)
    }

    /** A source-selection instruction is separate from the question to answer. */
    fun lookupPayload(prompt: String): String? {
        val value = prompt.trim().replace(Regex("(?i)^(?:please\\s+)?(?:use|search|check|verify (?:on|with)|look (?:it )?up on)\\s+(?:the\\s+)?(?:wikipedia|wikimedia|wikidata|web)\\b\\s*(?:(?:to (?:find|see|learn)|for|about|and tell me)\\s+)?"), "")
            .replace(Regex("(?i)^(?:please\\s+)?(?:look (?:it|this) up|verify this|check the facts)\\s*"), "").trim().trimEnd('.', '!', '?')
        return value.takeIf { it.isNotBlank() && it != prompt.trim().trimEnd('.', '!', '?') &&
            !Regex("(?i)^(?:for\\s+)?(?:that|this|it|me|please|on wikipedia|with wikipedia)$").matches(it) }
    }

    fun resolve(prompt: String, prior: Context, factual: (String) -> Boolean, explicit: (String) -> Boolean): Context {
        if (explicit(prompt)) {
            val payload = lookupPayload(prompt)
            return if (payload == null) prior else Context(payload, subject(payload))
        }
        val payload = correction.matchEntire(prompt.trim())?.groupValues?.get(1)
        if (payload != null && knowledge.containsMatchIn(payload) && factual(payload)) {
            return Context(payload, subject(payload))
        }
        if (payload != null && !knowledge.containsMatchIn(payload)) {
            val replacement = cleanSubject(payload)
            if (replacement != null && prior.question != null) {
                val updated = if (prior.subject != null) {
                    prior.question.replace(Regex(Regex.escape(prior.subject), RegexOption.IGNORE_CASE), replacement)
                } else if (referential.containsMatchIn(prior.question)) {
                    prior.question.replace(referential, replacement)
                } else {
                    prior.question + "\nSubject clarified by the user: " + replacement
                }
                return Context(updated, replacement)
            }
        }
        if (com.battlesbudz.jarvis.v2.chat.TurnContinuity.isCorrection(prompt) && !knowledge.containsMatchIn(prompt) && prior.question != null) {
            // A date explicitly retracted by the user cannot remain a query
            // constraint or be presented to Gemma as the resolved question.
            if (Regex("(?i)\\bi (?:didn['’]?t|did not) (?:say|mention)\\b.*\\b(?:year|date)\\b").containsMatchIn(prompt)) {
                val withoutYear = prior.question.replace(Regex("\\b(?:1[0-9]{3}|20[0-9]{2})\\b"), "").replace(Regex("\\s+"), " ").trim()
                val questionStart = Regex("(?i)\\b(?:who|what|where|when|how|why|which)\\b").find(withoutYear)?.range?.first ?: 0
                return Context(withoutYear.substring(questionStart), prior.subject)
            }
            return prior
        }
        val entity = subject(prompt)
        if (entity == null && prior.subject != null && knowledge.containsMatchIn(prompt) && referential.containsMatchIn(prompt)) {
            return Context(prompt.replace(referential, prior.subject), prior.subject)
        }
        return if (factual(prompt)) Context(prompt, entity) else Context()
    }
}
