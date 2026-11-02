package com.battlesbudz.jarvis.v2.memory

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ConversationMemoryTest {
    private var now = 1_700_000_000_000L
    private fun bridge(): Pair<MemoryOs, ConversationMemory> {
        val file = File.createTempFile("conversation-memory", ".json").apply { delete(); deleteOnExit() }
        val os = MemoryOs(file) { now }
        return os to ConversationMemory(os)
    }
    private fun input(id: String, text: String, source: ConversationMemorySource = ConversationMemorySource.TEXT) = FinalMemoryInput(id, "conversation-1", "call-1", source, text, now)

    @Test fun finalizedUsefulUserInputsBecomeApprovedAndAreIdempotent() {
        // No approval step (Battles, 2026-11-01): capture commits immediately.
        val (os, bridge) = bridge()
        val first = bridge.capture(input("one", "I prefer tea."))
        assertEquals(ConversationMemoryOutcome.PROPOSED, first.outcome)
        val stored = first.memory!!
        assertEquals(MemoryReviewStatus.APPROVED, stored.reviewStatus)
        assertEquals(ConversationMemoryOutcome.PROPOSED, bridge.capture(input("one", "I prefer tea.")).outcome)
        assertEquals(1, os.read().snapshot!!.memories.size)
        assertEquals("conversation", stored.source.provenance.first().kind)
        assertNotEquals("one", stored.source.eventId)
    }

    @Test fun draftsQuestionsCommandsVoiceFailuresAndRestrictedTextAreNeverProposed() {
        val (os, bridge) = bridge()
        assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("draft", "I prefer tea").copy(complete = false)).outcome)
        assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("question", "What is my favorite drink?")).outcome)
        assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("command", "/remember tea")).outcome)
        assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("voice", "I prefer tea", ConversationMemorySource.VOICE).copy(recognitionSucceeded = false)).outcome)
        assertEquals(ConversationMemoryOutcome.EXCLUDED, bridge.capture(input("secret", "Remember my password is hunter22")).outcome)
        assertTrue(os.read().snapshot!!.memories.isEmpty())
    }

    @Test fun approvedContextHasOneSnapshotTokenAndPendingDoesNotInvalidateIt() {
        // The pending record is built through the MemoryOs lifecycle primitive
        // directly: capture itself now commits immediately, so it can no longer
        // produce the pending state this token test exercises.
        val (os, bridge) = bridge()
        val pending = os.propose(MemoryProposal("I live in Austin", MemorySource("home", "final_text_input", now))).memory!!
        val before = bridge.approvedContext("where do I live", 500)
        assertNotNull(before.stateToken); assertTrue(before.packet!!.memories.isEmpty())
        os.approve(pending.id)
        val approved = bridge.approvedContext("where do I live", 500)
        assertNotEquals(before.stateToken, approved.stateToken)
        assertTrue(approved.packet!!.text.contains("Austin"))
        os.propose(MemoryProposal("I prefer tea", MemorySource("tea", "final_text_input", now)))
        assertEquals(approved.stateToken, bridge.approvedContext("where do I live", 500).stateToken)
        os.delete(pending.id)
        assertNotEquals(approved.stateToken, bridge.approvedContext("where do I live", 500).stateToken)
    }

    @Test fun conversationCaptureKeepsLegacyIdentityWhenWikiIsDerivedLater() {
        val (os, bridge) = bridge(); val finalized = input("legacy-capture", "I prefer tea.")
        val captured = bridge.capture(finalized).memory!!
        assertNull(captured.wikiAssignment)
        assertEquals(MemoryOutcome.APPROVED, os.assignWiki(captured.id, captured.revision, MemoryWikiAssignment(WikiCategory.PREFERENCES, "Tea")).outcome)
        assertEquals(ConversationMemoryOutcome.PROPOSED, bridge.capture(finalized).outcome)
        assertEquals(1, os.read().snapshot!!.memories.size)
    }

    @Test fun spokenPhrasingOfCoveredFactClassesIsCapturedAndApproved() {
        // The four statements from Battles's 2026-10-31 device test (build 1632), which
        // every one of returned IGNORED before the extractor widening. Since
        // 2026-11-01 capture commits immediately: no PENDING state anywhere.
        val (os, bridge) = bridge()
        val name = bridge.capture(input("name", "So my full name is Justin Lee Battles.", ConversationMemorySource.VOICE))
        assertEquals(ConversationMemoryOutcome.PROPOSED, name.outcome)
        assertEquals(MemoryCategory.PERSON, name.memory!!.category)
        val preference = bridge.capture(input("icecream", "I also love ice cream.", ConversationMemorySource.VOICE))
        assertEquals(ConversationMemoryOutcome.PROPOSED, preference.outcome)
        assertEquals(MemoryCategory.PREFERENCE, preference.memory!!.category)
        val past = bridge.capture(input("skateboard", "I used to skateboard when I was a teenager.", ConversationMemorySource.VOICE))
        assertEquals(ConversationMemoryOutcome.PROPOSED, past.outcome)
        assertEquals(MemoryCategory.FACT, past.memory!!.category)
        val travel = bridge.capture(input("countries", "Yeah, and I was an average traveler and I've been to 27 different countries.", ConversationMemorySource.VOICE))
        assertEquals(ConversationMemoryOutcome.PROPOSED, travel.outcome)
        assertEquals(MemoryCategory.FACT, travel.memory!!.category)
        val stored = os.read().snapshot!!.memories
        assertEquals(4, stored.size)
        assertTrue(stored.all { it.reviewStatus == MemoryReviewStatus.APPROVED })
    }

    @Test fun traveledToFormIsCapturedStandalone() {
        // Pins the extractor's biography branch for the exact spoken form the
        // multi-fact acceptance message carries as its middle segment.
        val (os, bridge) = bridge()
        val result = bridge.capture(input("travel", "I have traveled to 27 countries", ConversationMemorySource.VOICE))
        assertEquals(ConversationMemoryOutcome.PROPOSED, result.outcome)
        assertEquals(MemoryCategory.FACT, result.memory!!.category)
        assertEquals(MemoryReviewStatus.APPROVED, result.memory!!.reviewStatus)
        assertEquals(1, os.read().snapshot!!.memories.size)
    }

    @Test fun multiFactMessageBecomesOneApprovedRecordPerFact() {
        // Battles's 2026-11-01 device message, spoken as ONE message: three
        // facts used to land as a single blob record holding the raw message.
        val (os, bridge) = bridge()
        val result = bridge.capture(input("multi",
            "My name is Justin Lee Battles and I have traveled to 27 countries and I like ice cream",
            ConversationMemorySource.VOICE))
        assertEquals(ConversationMemoryOutcome.PROPOSED, result.outcome)
        val stored = os.read().snapshot!!.memories
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.reviewStatus == MemoryReviewStatus.APPROVED })
        val byCategory = stored.associateBy { it.category }
        assertEquals("My name is Justin Lee Battles", byCategory[MemoryCategory.PERSON]!!.content)
        assertEquals("I have traveled to 27 countries", byCategory[MemoryCategory.FACT]!!.content)
        assertEquals("I like ice cream", byCategory[MemoryCategory.PREFERENCE]!!.content)
        // Replay of the same message replays onto the same three records.
        assertEquals(ConversationMemoryOutcome.PROPOSED, bridge.capture(input("multi",
            "My name is Justin Lee Battles and I have traveled to 27 countries and I like ice cream",
            ConversationMemorySource.VOICE)).outcome)
        assertEquals(3, os.read().snapshot!!.memories.size)
    }

    @Test fun mixedFactAndQuestionMessageCapturesOnlyTheFact() {
        val (os, bridge) = bridge()
        val result = bridge.capture(input("mixed", "I like ice cream. What is the weather today?", ConversationMemorySource.VOICE))
        assertEquals(ConversationMemoryOutcome.PROPOSED, result.outcome)
        val stored = os.read().snapshot!!.memories
        assertEquals(1, stored.size)
        assertEquals("I like ice cream", stored.single().content)
        assertEquals(MemoryCategory.PREFERENCE, stored.single().category)
    }

    @Test fun leadInsDoNotSmuggleQuestionsOrCommandsPastTheGate() {
        val (os, bridge) = bridge()
        assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("question", "So what is my favorite drink?")).outcome)
        assertEquals(ConversationMemoryOutcome.IGNORED, bridge.capture(input("command", "!remember tea")).outcome)
        assertTrue(os.read().snapshot!!.memories.isEmpty())
    }

    @Test fun rememberThatStripsDirectiveAndClassifiesPreference() {
        val (_, bridge) = bridge()
        val result = bridge.capture(input("remember", "Remember that I prefer oolong tea."))
        assertEquals(ConversationMemoryOutcome.PROPOSED, result.outcome)
        assertEquals("I prefer oolong tea", result.memory!!.content)
        assertEquals(MemoryCategory.PREFERENCE, result.memory!!.category)
    }
}
