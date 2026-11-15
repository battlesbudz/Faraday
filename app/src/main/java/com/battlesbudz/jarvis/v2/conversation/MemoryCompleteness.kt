package com.battlesbudz.jarvis.v2.conversation

/**
 * Detects a generated answer that uses only part of the memory records the
 * turn actually carried.
 *
 * Retrieval, injection and disregard are separate problems, each with its
 * own guard. This one is about wholeness: on build 1677, asked "What do you
 * know about me?" with five records in the prompt — his name and 27
 * countries and ice cream in one, his name restated in a second, ice cream
 * as his favorite food in a third, wakeboarding in Dubai and skateboarding
 * in a fourth, and a greeting exchange in the fifth — Gemma answered "I
 * know you have traveled to twenty-seven countries and enjoy ice cream.
 * This information has been duly noted for future reference." Two facts of
 * five records, then a stop, and the tail of the answer parrots the second
 * record's restatement boilerplate without ever stating the name that
 * record holds. Battles's ruling: completeness applies to ALL memory
 * answers, broad and narrow — "all answers should be whole" — and the
 * packet header now says so (see
 * [com.battlesbudz.jarvis.v2.memory.MemoryRetrieval.packetFromRetrieved]);
 * this guard is the deterministic backstop, mirroring [MemoryDisregard]:
 * it runs only when at least one record was actually injected and the
 * disregard check passed, and the guards in [ConversationGeneration]
 * regenerate once with a correction that names what was left out. Unlike
 * the disregard guard there is no replacement fallback: when the
 * regeneration is still partial, the regenerated draft is published as-is,
 * because a partial but honest answer beats a stitched one — unless the
 * regeneration disregards the records, in which case the disregard
 * fallback governs as usual.
 *
 * One bound on "whole" (Battles, amending the ruling the same day): on a
 * broad question with a large packet — more than [TOP_FACTS_WINDOW]
 * records — wholeness is not a recital. The coverage set is the packet's
 * top [TOP_FACTS_WINDOW] records in packet order (the retrieval/profile
 * ranking; no new ranking signal is invented here), and a draft is whole
 * when it covers at least [MIN_TOP_FACTS_COVERED] of them including the
 * top-ranked one: the impression of full recall from the most important
 * facts, not a robotic read-out of every detail. Narrow questions are
 * unaffected; their wholeness is judged on the asked topic alone.
 *
 * One more coverage rule, for preference records: the salient units are
 * polarity-blind — the stopword list strips negations and preference
 * verbs alike, so "I don't like ice cream" and "You like ice cream"
 * reduce to the same units and the unit rules alone would call the
 * record covered by an answer that contradicts it. A preference record
 * therefore also needs polarity agreement: some clause of the draft that
 * touches the record's units must state the preference with the same
 * polarity as the record (a clause with no preference marker of its own,
 * a bare restatement of the topic, agrees with either). Only a draft
 * whose every touching clause flips the record's polarity leaves the
 * record uncovered.
 *
 * The check is pure string work on data already in the prompt: no model
 * call, no embedding, no network. A turn whose draft passes pays only the
 * check itself; the regeneration cost is paid only on turns where a
 * partial draft actually fires it.
 */
internal object MemoryCompleteness {

    /** Records in a broad question's coverage window, in packet order. */
    const val TOP_FACTS_WINDOW = 10

    /** Distinct top-window records a large-packet broad answer must cover. */
    const val MIN_TOP_FACTS_COVERED = 5

    /** The outcome of one completeness evaluation. */
    internal data class Assessment(
        /** In-scope records the answer leaves uncovered, in packet order. */
        val uncovered: List<String>,
        /** Top-window records the answer covers; only set for a large packet on a broad question. */
        val topFactsCovered: Int = 0,
        /** True when the top-facts window decided this assessment (large packet, broad question). */
        val largePacket: Boolean = false)

    /** A packet is large when it holds more records than the broad coverage window. */
    fun isLargePacket(contents: List<String>): Boolean = contents.size > TOP_FACTS_WINDOW

    fun assess(question: String, contents: List<String>, answer: String): Assessment {
        if (contents.isEmpty()) return Assessment(emptyList())
        val draft = salientUnits(answer)
        val broad = isBroadQuestion(question)
        if (broad && isLargePacket(contents)) {
            val top = contents.take(TOP_FACTS_WINDOW)
            val covered = top.map { broadCovered(it, draft, answer) }
            val coveredCount = covered.count { it }
            val uncovered = if (coveredCount >= MIN_TOP_FACTS_COVERED && covered.first()) emptyList()
                else top.filterIndexed { index, _ -> !covered[index] }
            return Assessment(uncovered, coveredCount, largePacket = true)
        }
        val nameQuestion = !broad && isNameQuestion(question)
        val questionUnits = if (broad) emptySet() else salientUnits(question)
        return Assessment(contents.filter { content ->
            isUncovered(content, broad, nameQuestion, questionUnits, draft, answer)
        })
    }

    /**
     * The injected records (their original contents, in packet order) that
     * [answer] leaves uncovered for [question]. Empty when the answer is
     * whole, when nothing was injected, and when no fact-bearing record is
     * in scope for the question.
     */
    fun uncoveredRecords(question: String, contents: List<String>, answer: String): List<String> =
        assess(question, contents, answer).uncovered

    fun isPartial(question: String, contents: List<String>, answer: String): Boolean =
        uncoveredRecords(question, contents, answer).isNotEmpty()

    /**
     * Appended to the submitted prompt for the one corrective regeneration.
     * Names what the draft left out, in the records' own words, because the
     * model cannot cover a fact it is not shown it dropped.
     */
    fun correction(uncovered: List<String>): String {
        val naming = naming(namedItems(uncovered, 5))
        return "\nThe previous draft answered only part of what the memory records included with " +
            "this message hold.$naming Answer the user's question again and cover everything in " +
            "those records that the question touches, including what was left out, without " +
            "dropping what the previous draft already stated correctly."
    }

    /**
     * The large-packet form of [correction]: names the most important
     * uncovered records from the top of the packet, capped so the facts the
     * draft already covered plus the named additions stay within the
     * window, and instructs the model to answer with the most important
     * facts — at most ten in total — woven naturally rather than recited.
     */
    fun topFactsCorrection(uncovered: List<String>, alreadyCovered: Int): String {
        val room = (TOP_FACTS_WINDOW - alreadyCovered).coerceAtLeast(0)
        val naming = naming(namedItems(uncovered, room))
        return "\nThe previous draft answered only part of what the memory records included with " +
            "this message hold.$naming Answer the user's question again with the most important " +
            "facts from those records, at most ten facts in total, woven naturally into the " +
            "answer rather than recited as a list, and do not drop the important facts the " +
            "previous draft already stated."
    }

    private fun namedItems(uncovered: List<String>, cap: Int): List<String> =
        uncovered.map { factSegment(it).replace(Regex("\\s+"), " ").trim().take(160).trimEnd() }
            .filter { it.isNotEmpty() }.distinct().take(cap)

    private fun naming(items: List<String>): String =
        if (items.isEmpty()) "" else " It left out: ${items.joinToString("; ")}."

    /** Broad coverage of one record; a record with no fact units is covered vacuously. */
    private fun broadCovered(content: String, draft: Set<String>, answer: String): Boolean {
        val fact = factSegment(content)
        val units = salientUnits(fact)
        if (units.isEmpty()) return true
        return coveredBroad(fact, units, draft, answer)
    }

    private fun isUncovered(content: String, broad: Boolean, nameQuestion: Boolean,
                            questionUnits: Set<String>, draft: Set<String>, answer: String): Boolean {
        val fact = factSegment(content)
        val units = salientUnits(fact)
        // No fact to cover: a greeting exchange ("hey" / "Hello. How can I
        // help you today?") carries no salient units and can never be the
        // part an answer left out.
        if (units.isEmpty()) return false
        if (broad) return !coveredBroad(fact, units, draft, answer)
        if (nameQuestion && nameMarker.containsMatchIn(fact)) {
            val names = nameUnits(fact)
            // A name question is answered whole only by stating the name;
            // another fact from the same compound record ("he has traveled
            // to 27 countries") does not cover its name record. A record
            // whose name was saved without capitalization has no name units
            // to demand, so it falls back to the generic narrow rule.
            if (names.isNotEmpty()) return names.intersect(draft).isEmpty()
        }
        return !coveredNarrow(fact, units, questionUnits, draft, answer)
    }

    /**
     * Broad coverage: the draft must touch the record at all, and — the
     * specimen's exact failure — a record that states the user's name is
     * not covered by its other facts alone. An answer that recites the
     * travel and food facts but never states the name has left the name
     * record out, so the draft must state the name too.
     */
    private fun coveredBroad(fact: String, units: Set<String>, draft: Set<String>, answer: String): Boolean {
        if (units.intersect(draft).isEmpty()) return false
        if (nameMarker.containsMatchIn(fact)) {
            val names = nameUnits(fact)
            if (names.isNotEmpty() && names.intersect(draft).isEmpty()) return false
        }
        return !polarityConflict(fact, units, answer)
    }

    /**
     * Narrow coverage, judged against what the question asks about: a
     * record is in scope only when it shares a salient unit with the
     * question, and covered only when the draft states at least one of the
     * record's own fact units — units the question itself did not supply —
     * so two records bearing on one topic both have to be answered from.
     * When in doubt a record stays out of scope: a missed firing costs
     * nothing, a false one costs the user a regeneration.
     */
    private fun coveredNarrow(fact: String, units: Set<String>, questionUnits: Set<String>,
                              draft: Set<String>, answer: String): Boolean {
        val shared = units.intersect(questionUnits)
        if (shared.isEmpty()) return true // out of scope for this question
        val own = units - questionUnits
        val stated = if (own.isEmpty()) shared.intersect(draft).isNotEmpty()
            else own.intersect(draft).isNotEmpty()
        if (!stated) return false
        return !polarityConflict(fact, units, answer)
    }

    // ---- preference polarity ----

    /**
     * True when [fact] states a preference and every clause of the draft
     * that touches the record's units states that preference with the
     * opposite polarity — the draft contradicts the record while naming
     * its topic, which the polarity-blind unit rules would otherwise
     * count as coverage. A draft clause with no preference marker of its
     * own agrees with either polarity: it restates the topic without
     * taking a side.
     */
    private fun polarityConflict(fact: String, units: Set<String>, answer: String): Boolean {
        val recordPolarity = preferencePolarity(fact)
        if (recordPolarity == 0) return false
        val covering = answerClauses(answer).filter { salientUnits(it).intersect(units).isNotEmpty() }
        if (covering.isEmpty()) return false
        return covering.none { clause ->
            val clausePolarity = preferencePolarity(clause)
            clausePolarity == 0 || clausePolarity == recordPolarity
        }
    }

    /**
     * The polarity a text states about a preference: +1 positive,
     * -1 negative, 0 when it carries no preference marker at all. A
     * negative-class verb (hate, dislike) states a negative preference on
     * its own; a negation token flips whichever marker is present, so
     * "don't like" is negative and "don't hate" is not.
     */
    private fun preferencePolarity(text: String): Int {
        val toks = tokens(text).toSet()
        val hasPositive = toks.any { it in positivePreferenceWords }
        val hasNegative = toks.any { it in negativePreferenceWords }
        if (!hasPositive && !hasNegative) return 0
        var negative = hasNegative
        if (toks.any { it in negationWords }) negative = !negative
        return if (negative) -1 else 1
    }

    /**
     * The draft's clauses: sentences, then "but" clauses within them, so
     * "You like cake, but you don't like ice cream" judges its ice-cream
     * clause on its own polarity instead of the whole sentence's.
     */
    private fun answerClauses(answer: String): List<String> =
        answer.split(Regex("[.!?;\\n]+"))
            .flatMap { it.split(Regex("\\bbut\\b", RegexOption.IGNORE_CASE)) }
            .filter { it.isNotBlank() }

    // Preference verbs and markers, in the raw token forms [tokens]
    // produces (lowercase, apostrophes removed). These words are
    // stopwords for salient units — facts are their objects, not the
    // verbs — but they carry the polarity a coverage check must respect.
    private val positivePreferenceWords = setOf(
        "like", "likes", "liked", "love", "loves", "loved", "enjoy", "enjoys", "enjoyed",
        "prefer", "prefers", "preferred", "want", "wants", "wanted", "favorite", "favourite")

    private val negativePreferenceWords = setOf(
        "hate", "hates", "hated", "dislike", "dislikes", "disliked")

    private val negationWords = setOf(
        "no", "not", "never", "cannot", "cant", "dont", "didnt", "doesnt", "havent", "hasnt",
        "hadnt", "isnt", "arent", "wasnt", "werent", "wont", "couldnt", "shouldnt", "wouldnt",
        "aint")

    // ---- question shape ----

    private val broadPatterns = listOf(
        Regex("\\babout\\s+(?:me|myself)\\b"),
        Regex("\\bwho\\s+am\\s+i\\b"),
        Regex("\\bwhat\\s+have\\s+i\\s+done\\b"),
        Regex("\\bi\\s+have\\s+done\\b"))

    /** Broad about-the-user questions put every injected record in scope. */
    fun isBroadQuestion(question: String): Boolean =
        broadPatterns.any { it.containsMatchIn(normalized(question)) }

    private fun isNameQuestion(question: String): Boolean =
        Regex("\\bmy\\s+name\\b").containsMatchIn(normalized(question))

    // The same marker MemoryRetrieval's name fallback uses: the record
    // states the user's name in the user's own words.
    private val nameMarker = Regex("\\b(?:my name|i am|i'm)\\b", RegexOption.IGNORE_CASE)

    private fun normalized(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").replace(Regex("\\s+"), " ").trim()

    // ---- record facts ----

    /**
     * The fact a record carries. Stored contents pair the user's own
     * statement with the app's restatement after a newline; the statement
     * is the fact. The specimen draft parroted a restatement's boilerplate
     * ("This information has been duly noted for future reference") without
     * stating what the record held, so the restatement never counts toward
     * coverage.
     */
    private fun factSegment(content: String): String =
        content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    /**
     * Proper-name units of a fact segment: capitalized tokens that do not
     * open a sentence. Sentence openers are capitalized for grammar, not
     * because they name anything ("Okay.", "Hello.").
     */
    private fun nameUnits(fact: String): Set<String> {
        val result = mutableSetOf<String>()
        var previousEnd = 0
        var first = true
        for (match in Regex("[\\p{L}]+").findAll(fact)) {
            val between = if (first) "" else fact.substring(previousEnd, match.range.first)
            val sentenceInitial = first || between.any { it == '.' || it == '!' || it == '?' }
            if (!sentenceInitial && match.value.first().isUpperCase()) {
                result += stem(match.value.lowercase())
            }
            first = false
            previousEnd = match.range.last + 1
        }
        return result
    }

    // ---- token work ----

    /**
     * The salient content units of a text: distinctive content tokens
     * (proper nouns, numbers, activity and object nouns), stemmed, with
     * stopwords, glue verbs and conversational formula removed. Numbers
     * compare in digit form on both sides, so a record's "27" and a draft's
     * "twenty-seven" — and the reverse — count as the same unit.
     */
    private fun salientUnits(text: String): Set<String> =
        tokens(text).filter { it !in stopWords }
            .map { stem(it) }
            .filter { it !in stopWords && it !in formulaWords }
            .toSet()

    private val tokenPattern = Regex("[\\p{L}\\p{N}]+")

    // "one" is deliberately not a number word: in these records it is a
    // pronoun far more often than a count.
    private val numberWords = mapOf(
        "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
        "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)

    private fun tokens(text: String): List<String> {
        val raw = tokenPattern.findAll(text.lowercase().replace("'", "")).map { it.value }
            .filter { it.length > 1 || it.all { c -> c.isDigit() } }.toList()
        val out = ArrayList<String>(raw.size)
        var total = 0
        var group = 0
        var inRun = false
        fun flush() {
            if (inRun) {
                out += (total + group).toString()
                total = 0
                group = 0
                inRun = false
            }
        }
        for (token in raw) {
            val value = numberWords[token]
            when {
                token.all { it.isDigit() } -> {
                    flush()
                    out += token.trimStart('0').ifEmpty { "0" }
                }
                value != null -> { group += value; inRun = true }
                token == "hundred" -> { group = maxOf(group, 1) * 100; inRun = true }
                token == "thousand" -> { total += maxOf(group, 1) * 1000; group = 0; inRun = true }
                else -> { flush(); out += token }
            }
        }
        flush()
        return out
    }

    /**
     * Crude uniform stemmer: one suffix strip, applied to records, question
     * and draft alike, so "wakeboarding" and "wakeboarded" meet at
     * "wakeboard" and "skateboarding" meets "skateboard". It only ever has
     * to agree with itself, never with a dictionary.
     */
    private fun stem(token: String): String {
        if (token.all { it.isDigit() }) return token
        return when {
            token.length > 4 && token.endsWith("ies") -> token.dropLast(3) + "y"
            token.length > 5 && token.endsWith("ing") -> token.dropLast(3)
            token.length > 4 && token.endsWith("ed") -> token.dropLast(2)
            token.length > 4 && token.endsWith("es") -> token.dropLast(2)
            token.length > 3 && token.endsWith("s") -> token.dropLast(1)
            else -> token
        }
    }

    private val stopWords = setOf(
        "a", "about", "after", "again", "also", "am", "an", "and", "are", "as", "at", "be", "been",
        "being", "but", "by", "can", "could", "did", "didnt", "do", "does", "doesnt", "dont", "for",
        "from", "get", "got", "had", "has", "have", "havent", "how", "i", "if", "im", "in", "into",
        "is", "isnt", "it", "its", "ive", "just", "know", "lets", "like", "love", "enjoy", "prefer",
        "hate", "dislike", "want", "many", "me", "more", "most", "much", "my", "no", "not", "of",
        "off", "on", "one", "or", "our", "out", "said", "say", "she", "should", "so", "than", "that",
        "thats", "the", "their", "them", "then", "there", "these", "they", "thing", "things", "this",
        "those", "through", "to", "too", "under", "up", "very", "was", "we", "were", "what", "whats",
        "when", "where", "which", "who", "why", "will", "with", "wont", "would", "you", "your",
        "youre", "he", "her", "his", "cant")

    // Conversational formula, never a fact about the user: greetings,
    // politeness, and the assistant's stock phrases.
    private val formulaWords = setOf(
        "hey", "hello", "hi", "greetings", "welcome", "okay", "ok", "please", "thank", "thanks",
        "sure", "yeah", "sir", "help", "today", "tomorrow", "yesterday", "now")
}
