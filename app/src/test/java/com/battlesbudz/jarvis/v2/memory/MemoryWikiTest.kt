package com.battlesbudz.jarvis.v2.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryWikiTest {
    private val now = 1_700_000_000_000L
    private fun memory(id: String, content: String, category: WikiCategory = WikiCategory.KNOWLEDGE, topic: String = "General", status: MemoryReviewStatus = MemoryReviewStatus.APPROVED, expiry: Long? = null, updated: Long = now) =
        MemoryRecord(id, content, MemoryCategory.FACT, MemoryTier.LONG_TERM, MemoryType.SEMANTIC, 80,
            MemorySource("${id.padEnd(32, '0').take(32)}", "manual", now), status, now, updated, 1, expiry, wikiAssignment = MemoryWikiAssignment(category, topic))

    @Test fun approvedNonexpiredOnlyPagesSearchAndLinks() {
        val active = memory("00000000-0000-4000-8000-000000000001", "Uses [[Málaga]] for a project", WikiCategory.PROJECTS, "Atlas")
        val place = memory("00000000-0000-4000-8000-000000000002", "Málaga notes", WikiCategory.PLACES, "Málaga")
        val pending = memory("00000000-0000-4000-8000-000000000003", "hidden secret", status = MemoryReviewStatus.PENDING)
        val expired = memory("00000000-0000-4000-8000-000000000004", "old secret", expiry = now - 1)
        val index = MemoryWiki.build(MemorySnapshot(1, listOf(active, place, pending, expired), emptyList()), now)
        assertEquals(6, index.categoryPages.size)
        assertEquals(listOf(active), index.search("atlas project"))
        assertTrue(index.search("hidden").isEmpty())
        val atlas = index.pages.first { it.title == "Atlas" }
        val malaga = index.pages.first { it.title == "Málaga" }
        assertEquals(listOf(malaga.id), atlas.linkedPageIds)
        assertEquals(listOf(atlas.id), malaga.backlinkPageIds)
    }

    @Test fun stableTopicIdsNormalizeWhitespaceCaseAndPunctuationWithoutDestroyingUnicode() {
        assertEquals(MemoryWiki.canonicalTopicId("  Project: Atlas! "), MemoryWiki.canonicalTopicId("project atlas"))
        assertNotEquals(MemoryWiki.canonicalTopicId("Málaga"), MemoryWiki.canonicalTopicId("Malaga"))
    }

    @Test fun suggestionUsesSpecificIdentityPeopleAndProjectTopicsWithoutBroadPlaceLeakage() {
        assertEquals(WikiCategory.ABOUT_YOU, MemoryWiki.suggest("My name is Alex", MemoryCategory.PERSON).category)
        assertEquals("Alex", MemoryWiki.suggest("my friend Alex likes tea").topic)
        assertEquals("Atlas", MemoryWiki.suggest("project Atlas is a local assistant").topic)
        assertEquals(WikiCategory.PEOPLE, MemoryWiki.suggest("my friend Alex is from X").category)
        assertNotEquals(MemoryWiki.canonicalTopicId("C"), MemoryWiki.canonicalTopicId("C++"))
    }

    @Test fun suggestionsPrioritizePlacesAndProjectPredicateBoundaries() {
        assertEquals(WikiCategory.PLACES, MemoryWiki.suggest("I'm based in Austin").category)
        assertEquals("Atlas", MemoryWiki.suggest("project Atlas uses local models").topic)
        assertEquals("Atlas", MemoryWiki.suggest("app Atlas needs an offline index").topic)
    }

    @Test fun indexesEveryApprovedMemoryBeyondRecallLimitAndExcludesInactiveStates() {
        val all = (1..60).map { number ->
            val category = WikiCategory.entries[number % WikiCategory.entries.size]
            memory("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}", "memory number $number searchable token$number", category, "Topic $number", updated = now + number)
        }
        val rejected = memory("00000000-0000-4000-8000-000000000201", "rejected token", status = MemoryReviewStatus.REJECTED)
        val superseded = memory("00000000-0000-4000-8000-000000000202", "superseded token", status = MemoryReviewStatus.SUPERSEDED)
        val expired = memory("00000000-0000-4000-8000-000000000203", "expired token", expiry = now - 1)
        val index = MemoryWiki.build(MemorySnapshot(1, all + listOf(rejected, superseded, expired), emptyList()), now)
        assertEquals(60, index.memories.size)
        assertEquals(6, index.categoryPages.size)
        assertEquals(listOf(all.first()), index.search("token1"))
        assertEquals(60, index.search("searchable").size)
        assertTrue(index.search("rejected").isEmpty())
        assertTrue(index.search("superseded").isEmpty())
        assertTrue(index.search("expired").isEmpty())
    }

    @Test fun ambiguousCrossCategoryReferencesNeverCreateWrongBacklinks() {
        val project = memory("00000000-0000-4000-8000-000000000010", "See [[Atlas]]", WikiCategory.PROJECTS, "Work")
        val a = memory("00000000-0000-4000-8000-000000000011", "Project Atlas", WikiCategory.PROJECTS, "Atlas")
        val b = memory("00000000-0000-4000-8000-000000000012", "Person Atlas", WikiCategory.PEOPLE, "Atlas")
        assertTrue(MemoryWiki.build(MemorySnapshot(1, listOf(project, a, b), emptyList()), now).pages.first { it.title == "Work" }.linkedPageIds.isEmpty())
    }
}
