package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryPolicyTest {
    private val now = 1_700_000_000_000L
    private fun proposal(content: String, source: MemorySource = MemorySource("evt", "manual", now)) = MemoryProposal(content, source)

    @Test fun rawFinancialAndIdentityContentAndRestrictedProvenanceAreExcluded() {
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("My SSN is 123-45-6789"), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("Checking account balance is $1,234.00"), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("safe", MemorySource("evt2", "manual", now, provenance = listOf(MemoryProvenance("bank", "x", restricted = true)))), now).outcome)
    }

    @Test fun metadataUsesOneBoundedCanonicalRestrictedSchema() {
        fun source(provenance: MemoryProvenance) = MemorySource("event", "manual", now, provenance = listOf(provenance))
        assertEquals(MemoryOutcome.INVALID, MemoryPolicy.assess(proposal("safe", source(MemoryProvenance("", "id"))), now).outcome)
        assertEquals(MemoryOutcome.INVALID, MemoryPolicy.assess(proposal("safe", source(MemoryProvenance("note", "x".repeat(161)))), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("safe", source(MemoryProvenance("note", "id", "My SSN is\n123-45-6789"))), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("safe", source(MemoryProvenance("bank_transaction", "id"))), now).outcome)
        val canonical = MemoryPolicy.canonicalize(proposal(" safe ", source(MemoryProvenance(" note ", " id ", "  "))))
        assertEquals("safe", canonical.content); assertEquals("note", canonical.source.provenance.single().kind); assertNull(canonical.source.provenance.single().label)
    }

    @Test fun multilineRestrictedContentAndFingerprintBoundariesAreSafe() {
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("My SSN is\n123-45-6789"), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("Checking account balance is\n$1,234.00"), now).outcome)
        val a = proposal("safe", MemorySource("evt", "manual", now, provenance = listOf(MemoryProvenance("note", "a:b", "c"))))
        val b = proposal("safe", MemorySource("evt", "manual", now, provenance = listOf(MemoryProvenance("note", "a", "b:c"))))
        val nullLabel = proposal("safe", MemorySource("evt", "manual", now, provenance = listOf(MemoryProvenance("note", "a", null))))
        val emptyLabel = proposal("safe", MemorySource("evt", "manual", now, provenance = listOf(MemoryProvenance("note", "a", ""))))
        assertNotEquals(MemoryPolicy.fingerprint(a), MemoryPolicy.fingerprint(b))
        assertEquals(MemoryPolicy.fingerprint(nullLabel), MemoryPolicy.fingerprint(emptyLabel))
    }

    @Test fun invalidBoundsAreHonestAndSourceKeysAreOpaque() {
        assertEquals(MemoryOutcome.INVALID, MemoryPolicy.assess(proposal(" "), now).outcome)
        assertEquals(MemoryOutcome.INVALID, MemoryPolicy.assess(proposal("x", MemorySource("evt", "manual", now + 90_000_000L)), now).outcome)
        assertNotEquals("a phrase from the caller", MemoryPolicy.sourceKey("a phrase from the caller"))
        assertEquals(MemoryPolicy.sourceKey("same"), MemoryPolicy.sourceKey("same"))
    }

    @Test fun passwordAndApiTokensAreExcludedWithoutClaimingPerfectDetection() {
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("password: hunter22"), now).outcome)
        assertEquals(MemoryOutcome.EXCLUDED, MemoryPolicy.assess(proposal("api key = abcdefghijkl"), now).outcome)
    }
}
