package com.battlesbudz.jarvis.v2.memory

import java.text.Normalizer
import java.util.Locale

/** A derived, always-fresh view of approved memories. It does not persist generated facts. */
data class MemoryWikiPage(
    val id: String,
    val title: String,
    val category: WikiCategory,
    val records: List<MemoryRecord>,
    val updatedAtMs: Long,
    val linkedPageIds: List<String> = emptyList(),
    val backlinkPageIds: List<String> = emptyList(),
)

data class MemoryWikiIndex(val pages: List<MemoryWikiPage>, val memories: List<MemoryRecord>) {
    val categoryPages: List<MemoryWikiPage> get() = pages.filter { it.id.startsWith("category:") }
    fun findMemory(id: String): MemoryRecord? = memories.firstOrNull { it.id == id }
    /** All query tokens must occur across title/category/content; never uses recall's result limit. */
    fun search(query: String): List<MemoryRecord> {
        val tokens = MemoryWiki.tokens(query)
        if (tokens.isEmpty()) return emptyList()
        return memories.filter { memory ->
            val assignment = memory.wikiAssignment ?: MemoryWiki.suggest(memory)
            val haystack = MemoryWiki.tokens("${assignment.category.title} ${assignment.topic} ${memory.content}").toSet()
            tokens.all { it in haystack }
        }
    }
}

object MemoryWiki {
    fun canonicalTopicId(topic: String): String {
        val normalized = Normalizer.normalize(topic.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val base = normalized.replace(Regex("[\\s\\p{Punct}]+"), "-").trim('-').ifEmpty { "general" }
        // Preserve symbols that distinguish real names (C vs C++) while treating ordinary separators as formatting.
        val significant = normalized.filter { it in "+#" }
        return if (significant.isEmpty()) base else "$base-s${significant.map { it.code.toString(16) }.joinToString("")}"
    }
    fun canonicalTopic(topic: String): String = topic.trim().replace(Regex("\\s+"), " ")
    internal fun tokens(value: String): List<String> = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    fun suggest(record: MemoryRecord): MemoryWikiAssignment = suggest(record.content, record.category)
    fun suggest(content: String, memoryCategory: MemoryCategory = MemoryCategory.FACT): MemoryWikiAssignment {
        val text = canonicalTopic(content)
        val category = when {
            Regex("^(?:i live in|i'm based in|i am based in|i'm from|i am from|i'm located in|i am located in)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> WikiCategory.PLACES
            Regex("^(?:my name is|i am|i'm|i work as)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> WikiCategory.ABOUT_YOU
            memoryCategory == MemoryCategory.PREFERENCE || Regex("\\b(?:prefer|like|love|hate|dislike|favorite)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> WikiCategory.PREFERENCES
            memoryCategory == MemoryCategory.PERSON || Regex("\\b(?:my friend|my partner|my mother|my father|my sister|my brother|my colleague|my manager)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> WikiCategory.PEOPLE
            Regex("\\b(?:project|app|repo(?:sitory)?|build|launch|roadmap)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> WikiCategory.PROJECTS
            else -> WikiCategory.KNOWLEDGE
        }
        val topic = when (category) {
            WikiCategory.PEOPLE -> Regex("\\b(?:my friend|my partner|my colleague|my manager)\\s+([\\p{L}][\\p{L} '\\-]{0,80}?)(?=\\s+(?:is|likes|loves|prefers|works|lives|from)\\b|[.!]|$)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()
            WikiCategory.PROJECTS -> Regex("\\b(?:project|app|repo(?:sitory)?)\\s+([\\p{L}\\p{N}][\\p{L}\\p{N} '\\-]{0,80}?)(?=\\s+(?:is|was|for|with|that|uses|needs|builds|supports)\\b|[.!]|$)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()
            WikiCategory.PLACES -> Regex("\\b(?:live in|based in|from|located in)\\s+([\\p{L}][\\p{L} '\\-]{1,80})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)?.trim()?.trimEnd('.', '!', ',')
            else -> null
        } ?: category.title
        return MemoryWikiAssignment(category, canonicalTopic(topic).take(120))
    }

    fun build(snapshot: MemorySnapshot, nowMs: Long): MemoryWikiIndex {
        val eligible = snapshot.memories.asSequence().filter { it.reviewStatus == MemoryReviewStatus.APPROVED && (it.expiresAtMs == null || it.expiresAtMs > nowMs) }
            .sortedWith(compareByDescending<MemoryRecord> { it.updatedAtMs }.thenBy { it.id }).toList()
        val byKey = eligible.groupBy { val assignment = it.wikiAssignment ?: suggest(it); assignment.category to canonicalTopicId(assignment.topic) }
        val pages = mutableListOf<MemoryWikiPage>()
        WikiCategory.entries.forEach { category ->
            val categoryRecords = eligible.filter { (it.wikiAssignment ?: suggest(it)).category == category }
            pages += MemoryWikiPage("category:${category.id}", category.title, category, categoryRecords, categoryRecords.maxOfOrNull { it.updatedAtMs } ?: 0L)
        }
        byKey.entries.sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second })).forEach { (key, records) ->
            val assignment = records.first().wikiAssignment ?: suggest(records.first())
            pages += MemoryWikiPage("topic:${key.first.id}:${key.second}", assignment.topic, key.first, records, records.maxOf { it.updatedAtMs })
        }
        val topicPages = pages.filter { it.id.startsWith("topic:") }
        val titleToPages = topicPages.groupBy { canonicalTopicId(it.title) }
        val linked = mutableMapOf<String, MutableSet<String>>()
        topicPages.forEach { page ->
            val refs = page.records.flatMap { Regex("\\[\\[([^\\]]{1,120})]]").findAll(it.content).map { canonicalTopicId(it.groupValues[1]) }.toList() }
            refs.mapNotNull { ref -> titleToPages[ref]?.singleOrNull() }.filter { it.id != page.id }.forEach { target -> linked.getOrPut(page.id) { linkedSetOf() }.add(target.id) }
        }
        return MemoryWikiIndex(pages.map { page -> page.copy(linkedPageIds = linked[page.id].orEmpty().sorted(), backlinkPageIds = linked.filterValues { page.id in it }.keys.sorted()) }, eligible)
    }
}
