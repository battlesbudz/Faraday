package com.battlesbudz.jarvis.v2.ai

class FactualityVerifier {
    fun buildPrompt(question: String, draft: String): String {
        return """
            You are a factuality gate for a local assistant.
            Decide whether reference evidence is needed before the draft is shown.
            Return exactly one word: PASS or LOOKUP.
            Return LOOKUP only when checking is likely to change or substantiate
            the answer:
            - The question asks for current, recent, or changeable information,
              such as present status, prices, schedules, availability, or the
              latest event.
            - The question asks for an exact date, number, quotation,
              attribution, ranking, or obscure detail about a specific named
              person, organization, place, work, or event, and the draft's
              specific claim is not firmly established.
            - The draft is unsure, internally inconsistent, confuses similarly
              named subjects, or makes a specific claim that cannot be
              confidently validated from ordinary knowledge.
            Return PASS when the draft directly answers from stable knowledge:
            - General explanations, common definitions, broad history or
              science, and well-known facts stated at a level the model can
              know confidently.
            - Reasoning, comparisons, recommendations, opinions, creative
              work, and open-ended conversation.
            - A named subject appears, but the draft makes only broad, firmly
              known claims about it.
            Do not return LOOKUP merely because a question is factual or
            historical, merely because a named subject appears, or merely
            because a source could add more detail. If a specific checkable
            claim is both central to the answer and genuinely uncertain,
            choose LOOKUP; otherwise choose PASS.
            
            Question:
            $question
            
            Draft:
            $draft
        """.trimIndent()
    }

    fun requestsLookup(verdict: String): Boolean {
        val normalized = verdict.trim().uppercase()
        return normalized.startsWith("LOOKUP") ||
            normalized.contains("NEEDS_WIKIPEDIA") ||
            normalized.contains("NEEDS_REFERENCE")
    }
}
