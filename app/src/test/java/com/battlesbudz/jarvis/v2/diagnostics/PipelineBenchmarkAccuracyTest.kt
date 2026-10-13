package com.battlesbudz.jarvis.v2.diagnostics

import org.junit.Assert.*
import org.junit.Test

class PipelineBenchmarkAccuracyTest {
    @Test fun substitutionsDeletionsAndInsertionsHaveTraceableCounts() {
        val s = PipelineBenchmarkAccuracyEvaluator.evaluate("where was the first taco bell founded", "where was first trucker bell founded please", "user_verified_original_recording")
        assertEquals(7, s.referenceWords); assertEquals(7, s.hypothesisWords)
        assertEquals(1, s.wordSubstitutions); assertEquals(1, s.wordDeletions); assertEquals(1, s.wordInsertions)
        assertEquals(3.0 / 7, s.wer!!, 0.0)
        assertEquals(PipelineBenchmarkAccuracyEvaluator.NORMALIZATION, s.normalization)
    }
    @Test fun normalizationIsVersionedCaseAndPunctuationInvariantWithUnicodeNfkc() {
        val score = PipelineBenchmarkAccuracyEvaluator.evaluate("ＴＡＣＯ Bell, opened!", "taco bell opened", "user_verified")
        assertEquals(0.0, score.wer!!, 0.0); assertEquals(0.0, score.cer!!, 0.0)
        assertNull(score.reference); assertNull(score.hypothesis)
    }
    @Test fun emptyReferenceIsNotPerfectAccuracyAndFalsePositiveSpeechIsVisible() {
        val score = PipelineBenchmarkAccuracyEvaluator.evaluate("", "thank you", "verified_silence")
        assertNull(score.wer); assertNull(score.cer); assertEquals(2, score.falsePositiveWords)
        assertEquals(8, score.falsePositiveCharacters)
        val empty = PipelineBenchmarkAccuracyEvaluator.evaluate("", "", "verified_silence")
        assertNull(empty.wer); assertEquals(0, empty.falsePositiveWords)
    }
    @Test fun insertionHeavyWerCanExceedOneAndIsNeverClamped() {
        val s = PipelineBenchmarkAccuracyEvaluator.evaluate("yes", "no this was something else entirely", "user_verified")
        assertEquals(6.0, s.wer!!, 0.0)
    }
    @Test fun characterErrorRateCountsUnicodeCodePointsWithoutWhitespace() {
        val score = PipelineBenchmarkAccuracyEvaluator.evaluate("abc", "adc", "user_verified")
        assertEquals(1, score.characterSubstitutions); assertEquals(0, score.characterDeletions)
        assertEquals(1.0 / 3, score.cer!!, 0.0)
        val unicode = PipelineBenchmarkAccuracyEvaluator.evaluate("𐐀", "𐐁", "user_verified")
        assertEquals(1, unicode.referenceCharacters); assertEquals(1, unicode.characterErrors)
    }
    @Test fun boundedEvaluationRejectsUnverifiedAndOversizedReferencesRatherThanTruncating() {
        assertThrows(IllegalArgumentException::class.java) { PipelineBenchmarkAccuracyEvaluator.evaluate("a", "b", "") }
        assertThrows(IllegalArgumentException::class.java) { PipelineBenchmarkAccuracyEvaluator.evaluate("a".repeat(16_385), "b", "verified") }
        assertThrows(IllegalArgumentException::class.java) { PipelineBenchmarkAccuracyEvaluator.evaluate("a".repeat(4_097), "b", "verified") }
        assertThrows(IllegalArgumentException::class.java) { PipelineBenchmarkAccuracyEvaluator.evaluate(List(1_025) { "a" }.joinToString(" "), "b", "verified") }
    }
    @Test fun equalCostAlignmentsKeepDeterministicSubstitutionPreference() {
        val score = PipelineBenchmarkAccuracyEvaluator.evaluate("a b", "b a", "verified")
        assertEquals(2, score.wordSubstitutions); assertEquals(0, score.wordDeletions); assertEquals(0, score.wordInsertions)
        assertEquals(2, score.characterSubstitutions); assertEquals(0, score.characterDeletions); assertEquals(0, score.characterInsertions)
    }
    @Test(timeout = 20_000) fun maximumCharacterInputRetainsCorrectCountsWithBoundedMemory() {
        val size = PipelineBenchmarkAccuracyEvaluator.MAX_NORMALIZED_CHARACTERS
        val score = PipelineBenchmarkAccuracyEvaluator.evaluate("a".repeat(size), "a".repeat(size - 1) + "b", "verified")
        assertEquals(size, score.referenceCharacters); assertEquals(1, score.characterSubstitutions)
        assertEquals(0, score.characterDeletions); assertEquals(0, score.characterInsertions)
        assertEquals(1.0 / size, score.cer!!, 0.0)
    }
    @Test fun primitiveRowsMatchCanonicalFullMatrixAcrossSmallAlignments() {
        val corpus = mutableListOf(emptyList<String>())
        var level = listOf(emptyList<String>())
        repeat(3) {
            level = level.flatMap { prefix -> listOf(prefix + "a", prefix + "b") }
            corpus.addAll(level)
        }
        for (reference in corpus) for (hypothesis in corpus) {
            val score = PipelineBenchmarkAccuracyEvaluator.evaluate(reference.joinToString(" "), hypothesis.joinToString(" "), "verified")
            assertEquals("$reference → $hypothesis", fullMatrixCounts(reference, hypothesis),
                Triple(score.wordSubstitutions, score.wordDeletions, score.wordInsertions))
        }
    }
    /** Independent full distance matrix and backwards canonical alignment; fixtures are tiny. */
    private fun fullMatrixCounts(reference: List<String>, hypothesis: List<String>): Triple<Int, Int, Int> {
        val matrix = Array(reference.size + 1) { IntArray(hypothesis.size + 1) }
        for (i in 0..reference.size) matrix[i][0] = i
        for (j in 0..hypothesis.size) matrix[0][j] = j
        for (i in 1..reference.size) for (j in 1..hypothesis.size) {
            matrix[i][j] = if (reference[i - 1] == hypothesis[j - 1]) matrix[i - 1][j - 1]
                else minOf(matrix[i - 1][j - 1], matrix[i - 1][j], matrix[i][j - 1]) + 1
        }
        var i = reference.size
        var j = hypothesis.size
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && reference[i - 1] == hypothesis[j - 1] -> { i--; j-- }
                i > 0 && j > 0 && matrix[i][j] == matrix[i - 1][j - 1] + 1 -> { substitutions++; i--; j-- }
                i > 0 && matrix[i][j] == matrix[i - 1][j] + 1 -> { deletions++; i-- }
                else -> { insertions++; j-- }
            }
        }
        return Triple(substitutions, deletions, insertions)
    }
}
