package com.battlesbudz.jarvis.v2.diagnostics

import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Reference provenance is supplied by the evaluator, never inferred from a fluent model reply. */
data class PipelineBenchmarkAccuracy(
    val referenceProvenance: String,
    val normalization: String,
    val referenceWords: Int,
    val hypothesisWords: Int,
    val wordSubstitutions: Int,
    val wordDeletions: Int,
    val wordInsertions: Int,
    val referenceCharacters: Int,
    val hypothesisCharacters: Int,
    val characterSubstitutions: Int,
    val characterDeletions: Int,
    val characterInsertions: Int,
    val reference: String? = null,
    val hypothesis: String? = null
) {
    init {
        require(referenceProvenance.isNotBlank())
        require(listOf(referenceWords, hypothesisWords, wordSubstitutions, wordDeletions, wordInsertions,
            referenceCharacters, hypothesisCharacters, characterSubstitutions, characterDeletions, characterInsertions).all { it >= 0 })
        require(wordSubstitutions + wordDeletions <= referenceWords && wordSubstitutions + wordInsertions <= hypothesisWords)
        require(referenceWords - wordDeletions + wordInsertions == hypothesisWords)
        require(characterSubstitutions + characterDeletions <= referenceCharacters && characterSubstitutions + characterInsertions <= hypothesisCharacters)
        require(referenceCharacters - characterDeletions + characterInsertions == hypothesisCharacters)
    }
    val wordErrors get() = wordSubstitutions + wordDeletions + wordInsertions
    val characterErrors get() = characterSubstitutions + characterDeletions + characterInsertions
    /** WER/CER are ratios; insertions can legitimately produce a value greater than 1. */
    val wer: Double? get() = referenceWords.takeIf { it > 0 }?.let { wordErrors.toDouble() / it }
    val cer: Double? get() = referenceCharacters.takeIf { it > 0 }?.let { characterErrors.toDouble() / it }
    val falsePositiveWords get() = if (referenceWords == 0) hypothesisWords else 0
    val falsePositiveCharacters get() = if (referenceCharacters == 0) hypothesisCharacters else 0
    fun json(includeText: Boolean = false): JSONObject = JSONObject()
        .put("referenceProvenance", referenceProvenance).put("normalization", normalization)
        .put("referenceWords", referenceWords).put("hypothesisWords", hypothesisWords)
        .put("wordSubstitutions", wordSubstitutions).put("wordDeletions", wordDeletions).put("wordInsertions", wordInsertions)
        .put("referenceCharacters", referenceCharacters).put("hypothesisCharacters", hypothesisCharacters)
        .put("characterSubstitutions", characterSubstitutions).put("characterDeletions", characterDeletions)
        .put("characterInsertions", characterInsertions).put("wer", wer ?: JSONObject.NULL).put("cer", cer ?: JSONObject.NULL)
        .put("falsePositiveWords", falsePositiveWords).put("falsePositiveCharacters", falsePositiveCharacters)
        .also { if (includeText) it.put("reference", reference ?: JSONObject.NULL).put("hypothesis", hypothesis ?: JSONObject.NULL) }

    companion object {
        fun read(j: JSONObject) = PipelineBenchmarkAccuracy(
            j.getString("referenceProvenance"), j.getString("normalization"),
            j.getInt("referenceWords"), j.getInt("hypothesisWords"), j.getInt("wordSubstitutions"),
            j.getInt("wordDeletions"), j.getInt("wordInsertions"), j.getInt("referenceCharacters"),
            j.getInt("hypothesisCharacters"), j.getInt("characterSubstitutions"),
            j.getInt("characterDeletions"), j.getInt("characterInsertions"),
            j.benchmarkString("reference"), j.benchmarkString("hypothesis")
        )
    }
}

/** Deterministic corpus evaluation. No hypothesis is scored without an explicit verified reference. */
object PipelineBenchmarkAccuracyEvaluator {
    const val NORMALIZATION = "nfkc-root-lower-alnum-whitespace-v1"
    const val MAX_INPUT_CHARACTERS = 16_384
    const val MAX_WORDS = 1_024
    const val MAX_NORMALIZED_CHARACTERS = 4_096

    fun evaluate(reference: String, hypothesis: String, referenceProvenance: String,
                 retainText: Boolean = false): PipelineBenchmarkAccuracy {
        require(referenceProvenance.isNotBlank()) { "Accuracy requires verified reference provenance" }
        require(reference.length <= MAX_INPUT_CHARACTERS && hypothesis.length <= MAX_INPUT_CHARACTERS) {
            "Accuracy input exceeds the bounded evaluator limit"
        }
        val r = normalize(reference)
        val h = normalize(hypothesis)
        val rw = words(r)
        val hw = words(h)
        // CER excludes whitespace after word normalization. Count Unicode code points, not UTF-16 units.
        val rc = r.filterNot(Char::isWhitespace).codePoints().toArray().toList()
        val hc = h.filterNot(Char::isWhitespace).codePoints().toArray().toList()
        require(rw.size <= MAX_WORDS && hw.size <= MAX_WORDS && rc.size <= MAX_NORMALIZED_CHARACTERS && hc.size <= MAX_NORMALIZED_CHARACTERS) {
            "Normalized accuracy input exceeds the bounded evaluator limit"
        }
        val w = edits(rw, hw)
        val c = edits(rc, hc)
        return PipelineBenchmarkAccuracy(referenceProvenance, NORMALIZATION, rw.size, hw.size,
            w.substitutions, w.deletions, w.insertions, rc.size, hc.size,
            c.substitutions, c.deletions, c.insertions,
            reference.takeIf { retainText }, hypothesis.takeIf { retainText })
    }

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")
    private fun words(text: String): List<String> = text.takeIf(String::isNotBlank)?.split(' ') ?: emptyList()
    private data class Edits(val substitutions: Int = 0, val deletions: Int = 0, val insertions: Int = 0)
    /** Eight reusable primitive rows keep memory linear without per-cell objects or lists.
     * Ties prefer substitution, deletion, insertion, in that order, preserving normalization v1 counts.
     */
    private fun <T> edits(reference: List<T>, hypothesis: List<T>): Edits {
        val width = hypothesis.size + 1
        var previousCost = IntArray(width) { it }
        var previousSubstitutions = IntArray(width)
        var previousDeletions = IntArray(width)
        var previousInsertions = IntArray(width) { it }
        var currentCost = IntArray(width)
        var currentSubstitutions = IntArray(width)
        var currentDeletions = IntArray(width)
        var currentInsertions = IntArray(width)
        for (i in reference.indices) {
            currentCost[0] = i + 1
            currentSubstitutions[0] = 0
            currentDeletions[0] = i + 1
            currentInsertions[0] = 0
            for (j in hypothesis.indices) {
                val column = j + 1
                if (reference[i] == hypothesis[j]) {
                    currentCost[column] = previousCost[j]
                    currentSubstitutions[column] = previousSubstitutions[j]
                    currentDeletions[column] = previousDeletions[j]
                    currentInsertions[column] = previousInsertions[j]
                } else {
                    val substitute = previousCost[j] + 1
                    val delete = previousCost[column] + 1
                    val insert = currentCost[j] + 1
                    when {
                        substitute <= delete && substitute <= insert -> {
                            currentCost[column] = substitute
                            currentSubstitutions[column] = previousSubstitutions[j] + 1
                            currentDeletions[column] = previousDeletions[j]
                            currentInsertions[column] = previousInsertions[j]
                        }
                        delete <= insert -> {
                            currentCost[column] = delete
                            currentSubstitutions[column] = previousSubstitutions[column]
                            currentDeletions[column] = previousDeletions[column] + 1
                            currentInsertions[column] = previousInsertions[column]
                        }
                        else -> {
                            currentCost[column] = insert
                            currentSubstitutions[column] = currentSubstitutions[j]
                            currentDeletions[column] = currentDeletions[j]
                            currentInsertions[column] = currentInsertions[j] + 1
                        }
                    }
                }
            }
            var spare = previousCost; previousCost = currentCost; currentCost = spare
            spare = previousSubstitutions; previousSubstitutions = currentSubstitutions; currentSubstitutions = spare
            spare = previousDeletions; previousDeletions = currentDeletions; currentDeletions = spare
            spare = previousInsertions; previousInsertions = currentInsertions; currentInsertions = spare
        }
        return Edits(previousSubstitutions.last(), previousDeletions.last(), previousInsertions.last())
    }
}
