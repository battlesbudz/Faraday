package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryArchivePolicyTest {
    private val now = 1_700_000_000_000L
    private fun input(text: String) = FinalMemoryInput("event-private", "conversation-private", "call-private", ConversationMemorySource.TEXT, text, now)

    @Test fun secretsIncludingShortCodesAndLateSecretsExcludeEntireSource() {
        listOf("My password is x", "PIN: 1234", "Door code 9876", "OTP 654321", "Card number 4111 1111 1111 1111", "4111111111111111", "api_key: abcdef", "123456", "x".repeat(4_000) + " password: hunter22").forEach {
            assertEquals(it.take(32), SourceArchiveOutcome.EXCLUDED, MemoryArchivePolicy.prepare(input(it), now).rejection)
        }
    }

    @Test fun usefulHealthFinancialAndFamilyTextCanBeArchivedWithoutSecretIdentifiers() {
        listOf("My bank balance is $500", "My credit score is 720", "My doctor appointment is Monday", "My sister prefers tea").forEach {
            assertNotNull(MemoryArchivePolicy.prepare(input(it), now).episode)
        }
    }

    @Test fun draftsVoiceFailuresInvalidMetadataAndOversizedTextAreRejectedBeforePersistence() {
        assertEquals(SourceArchiveOutcome.IGNORED, MemoryArchivePolicy.prepare(input("tea").copy(complete = false), now).rejection)
        assertEquals(SourceArchiveOutcome.IGNORED, MemoryArchivePolicy.prepare(input("tea").copy(source = ConversationMemorySource.VOICE, recognitionSucceeded = false), now).rejection)
        assertEquals(SourceArchiveOutcome.INVALID, MemoryArchivePolicy.prepare(input("tea").copy(capturedAtMs = now + 1), now).rejection)
        assertEquals(SourceArchiveOutcome.INVALID, MemoryArchivePolicy.prepare(input("tea").copy(eventId = ""), now).rejection)
        assertEquals(SourceArchiveOutcome.INVALID, MemoryArchivePolicy.prepare(input("x".repeat(MemoryArchivePolicy.MAX_TEXT_CHARS + 1)), now).rejection)
    }

    @Test fun originalCaptureSetsExactExpiryAndReplayDoesNotMoveIt() {
        val source = input("We discussed sapphire notebooks")
        val first = MemoryArchivePolicy.prepare(source, now).episode!!
        val later = MemoryArchivePolicy.prepare(source, now + 1_000).episode!!
        assertEquals(first, later)
        assertEquals(now + MemoryArchivePolicy.RETENTION_MS, first.expiresAtMs)
        assertNotNull(MemoryArchivePolicy.prepare(source, first.expiresAtMs - 1).episode)
        assertEquals(SourceArchiveOutcome.EXPIRED, MemoryArchivePolicy.prepare(source, first.expiresAtMs).rejection)
    }

    @Test fun sourceIdentifiersAreOpaqueAndFingerprintDistinguishesChangedSource() {
        val first = MemoryArchivePolicy.prepare(input("tea"), now).episode!!
        assertEquals(MemoryPolicy.sourceKey("event-private"), first.eventKey)
        assertTrue(MemoryPolicy.isOpaqueEventKey(first.conversationKey))
        assertTrue(MemoryPolicy.isOpaqueEventKey(first.callKey!!))
        assertNotEquals(first.fingerprint, MemoryArchivePolicy.prepare(input("coffee"), now).episode!!.fingerprint)
        assertNotEquals(first.fingerprint, MemoryArchivePolicy.prepare(input("tea").copy(source = ConversationMemorySource.VOICE), now).episode!!.fingerprint)
    }

    @Test fun archiveIsOptionalAndArchiveFailureDoesNotCreateUntraceableFact() {
        val file = java.io.File.createTempFile("archive-boundary", ".json").apply { delete(); deleteOnExit() }
        val os = MemoryOs(file) { now }
        val failing = object : MemorySourceArchive {
            override fun captureSource(input: FinalMemoryInput) = SourceArchiveCapture(SourceArchiveOutcome.STORAGE_FAILURE)
            override fun searchExplicitHistory(query: String, limit: Int) = SourceArchiveSearch(SourceArchiveOutcome.STORAGE_FAILURE)
            override fun purgeExpiredSources() = false
        }
        assertEquals(ConversationMemoryOutcome.STORAGE_FAILURE, ConversationMemory(os, failing).capture(input("I prefer tea")).outcome)
        assertTrue(os.read().snapshot!!.memories.isEmpty())
        assertEquals(ConversationMemoryOutcome.PROPOSED, ConversationMemory(os).capture(input("I prefer tea")).outcome)
    }
}
