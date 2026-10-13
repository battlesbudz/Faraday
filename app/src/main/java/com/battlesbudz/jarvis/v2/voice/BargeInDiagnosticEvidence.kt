package com.battlesbudz.jarvis.v2.voice

/** Bounded text evidence only; never retains microphone PCM or changes a gate decision. */
internal class BargeInDiagnosticEvidence {
    private val records = linkedMapOf<String, String>()
    private var omitted = 0

    fun record(key: String, revision: Long, ageMs: Long, reason: String, transcript: String,
               reference: String, gate: BargeInGate, evaluated: Boolean) {
        records[key] = "revision=$revision resultAgeMs=$ageMs reason=$reason evaluated=$evaluated " +
            "transcriptChars=${transcript.length} referenceChars=${reference.length} " +
            "transcript=${quote(transcript, 260)} referenceTail=${quote(reference.takeLast(260), 260)} " +
            "referenceTailOnly=${reference.length > 260} " +
            "echoMatchedWords=${if (evaluated) gate.echoMatchedWords else -1} " +
            "fragments=${quote(if (evaluated) gate.examinedFragments.joinToString(" | ") else "not_evaluated", 240)} " +
            "selected=${quote(if (evaluated) gate.selectedRequest else "not_evaluated", 160)}"
        if (records.size > 6) {
            records.remove(records.keys.elementAt(2)) // Preserve first two and latest four results.
            omitted++
        }
    }

    fun entries(): List<String> = records.values.mapIndexed { index, value ->
        "barge_evidence_$index retained=${records.size} omitted=$omitted $value"
    }

    private fun quote(text: String, limit: Int): String {
        val encoded = buildString {
            for (c in text) {
                if (length >= limit) break
                when {
                    c == '\\' || c == '"' -> { append('\\'); append(c) }
                    c.isISOControl() || c == '\u2028' || c == '\u2029' -> append(' ')
                    else -> append(c)
                }
            }
        }
        // Explicit truncation marker; quoted fields remain on one diagnostic line.
        val shortened = text.length > limit || encoded.length >= limit
        return "\"$encoded${if (shortened) "…" else ""}\""
    }
}
