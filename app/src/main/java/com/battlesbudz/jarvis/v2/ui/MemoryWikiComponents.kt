package com.battlesbudz.jarvis.v2.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.battlesbudz.jarvis.v2.memory.*
import kotlinx.coroutines.launch

@Composable
internal fun MemoryArticle(
    page: MemoryWikiPage,
    index: MemoryWikiIndex?,
    allRecords: List<MemoryRecord>,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenRecord: (MemoryRecord) -> Unit,
    highlightedId: String?,
) {
    var tab by rememberSaveable(page.id) { mutableStateOf(0) }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val topicPages = index?.pages.orEmpty().filter { it.id.startsWith("topic:") && it.category == page.category }
    LaunchedEffect(page.id, highlightedId) {
        highlightedId?.let { id -> page.records.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { fact ->
            val offset = if (page.id.startsWith("category:")) 3 + topicPages.size else 2
            state.scrollToItem(fact + offset)
        } }
    }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 16.dp).testTag("memory_article_back")) { Text("${page.category.title}  /  ${page.title}") }
        Text(page.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text("${page.records.size} approved fact${if (page.records.size == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        TabRow(tab, modifier = Modifier.padding(horizontal = 16.dp)) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Article") }, modifier = Modifier.testTag("memory_article_tab"))
            Tab(tab == 1, { tab = 1 }, text = { Text("Sources") }, modifier = Modifier.testTag("memory_sources_tab"))
            Tab(tab == 2, { tab = 2 }, text = { Text("History") }, modifier = Modifier.testTag("memory_history_article_tab"))
        }
        when (tab) {
            0 -> LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text("Contents", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { scope.launch { state.animateScrollToItem(if (page.id.startsWith("category:")) 3 + topicPages.size else 2) } }, modifier = Modifier.testTag("memory_article_contents_facts")) { Text("Facts") }
                        if (topicPages.isNotEmpty() && page.id.startsWith("category:")) TextButton(onClick = { scope.launch { state.animateScrollToItem(2) } }, modifier = Modifier.testTag("memory_article_contents_topics")) { Text("Topics") }
                    }
                }
                if (page.id.startsWith("category:") && topicPages.isNotEmpty()) {
                    item { Text("Topics", style = MaterialTheme.typography.titleMedium) }
                    items(topicPages, key = { it.id }) { topic -> TextButton(onClick = { onOpenPage(topic.id) }, modifier = Modifier.fillMaxWidth().testTag("memory_topic_row_${topic.id}")) { Text(topic.title, Modifier.weight(1f)); Text("${topic.records.size}") } }
                }
                item { Text("Approved facts", style = MaterialTheme.typography.titleMedium) }
                if (page.records.isEmpty()) item { Text("No approved facts in this page yet.") }
                items(page.records, key = { it.id }) { record -> ArticleFactCard(record, highlighted = record.id == highlightedId, onClick = { onOpenRecord(record) }) }
                if (page.linkedPageIds.isNotEmpty()) { item { Text("Linked pages", style = MaterialTheme.typography.titleMedium) }; items(page.linkedPageIds, key = { "link:$it" }) { id -> index?.pages?.firstOrNull { it.id == id }?.let { linked -> TextButton(onClick = { onOpenPage(id) }) { Text(linked.title) } } } }
                if (page.backlinkPageIds.isNotEmpty()) { item { Text("Backlinks", style = MaterialTheme.typography.titleMedium) }; items(page.backlinkPageIds, key = { "back:$it" }) { id -> index?.pages?.firstOrNull { it.id == id }?.let { backlink -> TextButton(onClick = { onOpenPage(id) }) { Text(backlink.title) } } } }
            }
            1 -> SourceList(page.records)
            else -> LineageList(page.records, allRecords, onOpenRecord)
        }
    }
}

@Composable private fun ArticleFactCard(record: MemoryRecord, highlighted: Boolean, onClick: () -> Unit) = Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("memory_article_fact_${record.id}"), colors = CardDefaults.cardColors(containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(14.dp)) { Text(record.content); Text("${record.placement().first.title} · ${record.placement().second}", style = MaterialTheme.typography.labelSmall); Text("Source · ${record.source.sourceLabel()}", style = MaterialTheme.typography.labelSmall) } }

@Composable private fun SourceList(rows: List<MemoryRecord>) = LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { if (rows.isEmpty()) item { Text("No approved sources in this page.") }; items(rows, key = { it.id }) { record -> Card(Modifier.fillMaxWidth().testTag("memory_source_${record.id}")) { Column(Modifier.padding(14.dp)) { Text(record.content, style = MaterialTheme.typography.bodyMedium); Text("Captured from ${record.source.sourceLabel()} · ${dateLabel(record.source.createdAtMs)}", style = MaterialTheme.typography.labelSmall); if (record.source.provenance.isEmpty()) Text("No additional provenance was recorded.", style = MaterialTheme.typography.labelSmall) else record.source.provenance.forEach { source -> Text("${source.label ?: source.kind} · ${source.id}", style = MaterialTheme.typography.labelSmall) } } } } }

@Composable private fun LineageList(rows: List<MemoryRecord>, all: List<MemoryRecord>, onOpen: (MemoryRecord) -> Unit) {
    val lineage = remember(rows, all) {
        val ids = linkedSetOf<String>()
        fun addBranch(id: String) {
            if (!ids.add(id)) return
            all.firstOrNull { it.id == id }?.correctsMemoryId?.let(::addBranch)
            all.filter { it.correctsMemoryId == id }.forEach { addBranch(it.id) }
        }
        rows.forEach { addBranch(it.id) }
        all.filter { it.id in ids }.sortedBy { it.createdAtMs }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (lineage.isEmpty()) item { Text("No history in this page.") }
        items(lineage, key = { it.id }) { record -> Card(Modifier.fillMaxWidth().clickable { onOpen(record) }.testTag("memory_history_${record.id}")) { Column(Modifier.padding(14.dp)) {
            Text(record.content); Text("${record.reviewStatus.name.lowercase().replaceFirstChar { it.titlecase() }} · ${dateLabel(record.updatedAtMs)}", style = MaterialTheme.typography.labelSmall)
            if (record.correctsMemoryId != null) Text("Correction of an earlier saved fact", style = MaterialTheme.typography.labelSmall)
        } } }
    }
}

@Composable
internal fun MemoryDetail(record: MemoryRecord, allRecords: List<MemoryRecord>, onBack: () -> Unit, onCorrect: (MemoryRecord) -> Unit, onOrganize: (MemoryRecord) -> Unit, onDelete: (MemoryRecord) -> Unit, onApprove: (MemoryRecord) -> Unit = {}, onReject: (MemoryRecord) -> Unit = {}) {
    val placement = record.placement()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TextButton(onClick = onBack, modifier = Modifier.testTag("memory_detail_back")) { Text("Back to memory") } }
        item { Text("Memory detail", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("memory_detail_${record.id}")) }
        item { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(record.content, style = MaterialTheme.typography.bodyLarge); Text("${record.reviewStatus.name.lowercase().replaceFirstChar { it.titlecase() }}, ${placement.first.title} · ${placement.second}", style = MaterialTheme.typography.labelSmall) } } }
        item { Text("Source", style = MaterialTheme.typography.titleMedium) }
        item { Text("Captured from ${record.source.sourceLabel()} on ${dateLabel(record.source.createdAtMs)}") }
        if (record.source.provenance.isNotEmpty()) item { record.source.provenance.forEach { source -> Text("${source.label ?: source.kind} · ${source.id}", style = MaterialTheme.typography.bodySmall) } }
        item { Text("History", style = MaterialTheme.typography.titleMedium) }
        item { Text("Created ${dateLabel(record.createdAtMs)} · updated ${dateLabel(record.updatedAtMs)}", style = MaterialTheme.typography.bodySmall); if (record.correctsMemoryId != null) Text("This is a correction of an earlier saved fact.", style = MaterialTheme.typography.bodySmall); if (allRecords.any { it.correctsMemoryId == record.id }) Text("This fact has a later correction in memory history.", style = MaterialTheme.typography.bodySmall) }
        item { DetailActions(record, onCorrect, onOrganize, onDelete, onApprove, onReject) }
    }
}

@Composable private fun DetailActions(record: MemoryRecord, onCorrect: (MemoryRecord) -> Unit, onOrganize: (MemoryRecord) -> Unit, onDelete: (MemoryRecord) -> Unit, onApprove: (MemoryRecord) -> Unit, onReject: (MemoryRecord) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { if (record.reviewStatus == MemoryReviewStatus.PENDING) { Row { TextButton(onClick = { onApprove(record) }, modifier = Modifier.testTag("memory_approve")) { Text("Approve") }; TextButton(onClick = { onReject(record) }, modifier = Modifier.testTag("memory_reject")) { Text("Reject") } } }; if (record.reviewStatus == MemoryReviewStatus.APPROVED) TextButton(onClick = { onCorrect(record) }, modifier = Modifier.testTag("memory_correct")) { Text("Correct") }; if (record.reviewStatus in setOf(MemoryReviewStatus.PENDING, MemoryReviewStatus.APPROVED)) TextButton(onClick = { onOrganize(record) }, modifier = Modifier.testTag("memory_organize")) { Text("Organize") }; TextButton(onClick = { onDelete(record) }, modifier = Modifier.testTag("memory_delete")) { Text("Erase") } }

@Composable
internal fun ReviewList(rows: List<MemoryRecord>, busy: Boolean, onApprove: (MemoryRecord) -> Unit, onReject: (MemoryRecord) -> Unit, onCorrect: (MemoryRecord) -> Unit, onOrganize: (MemoryRecord) -> Unit, onDelete: (MemoryRecord) -> Unit, onOpen: (MemoryRecord) -> Unit) = LedgerList(rows, "Nothing is waiting for review.\n\nTell Jarvis “Remember that …” in Chat or Voice, or add a memory here. New memories appear for review before entering the wiki.", busy, onApprove, onReject, onCorrect, onOrganize, onDelete, onOpen, review = true)
@Composable
internal fun HistoryList(rows: List<MemoryRecord>, busy: Boolean, onCorrect: (MemoryRecord) -> Unit, onOrganize: (MemoryRecord) -> Unit, onDelete: (MemoryRecord) -> Unit, onOpen: (MemoryRecord) -> Unit, onEraseAll: () -> Unit) = LedgerList(rows.sortedByDescending { it.updatedAtMs }, "No memory history yet.", busy, {}, {}, onCorrect, onOrganize, onDelete, onOpen, review = false, onEraseAll = onEraseAll)

@Composable
private fun LedgerList(
    rows: List<MemoryRecord>, empty: String, busy: Boolean,
    onApprove: (MemoryRecord) -> Unit, onReject: (MemoryRecord) -> Unit,
    onCorrect: (MemoryRecord) -> Unit, onOrganize: (MemoryRecord) -> Unit,
    onDelete: (MemoryRecord) -> Unit, onOpen: (MemoryRecord) -> Unit,
    review: Boolean, onEraseAll: () -> Unit = {},
) = LazyColumn(
    modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
) {
    if (rows.isEmpty()) item { Text(empty) }
    else if (!review) item {
        TextButton(onClick = onEraseAll, enabled = !busy, modifier = Modifier.testTag("memory_erase_all")) {
            Text("Erase all memories")
        }
    }
    items(rows, key = { it.id }) { record ->
        Card(Modifier.fillMaxWidth().clickable { onOpen(record) }) {
            Column(Modifier.padding(14.dp)) {
                Text(record.content)
                val placement = record.placement()
                Text("${record.reviewStatus.name.lowercase().replaceFirstChar { it.titlecase() }} · ${placement.first.title} · ${placement.second}", style = MaterialTheme.typography.labelSmall)
                Text("Captured from ${record.source.sourceLabel()} · ${dateLabel(record.source.createdAtMs)}", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (review) {
                        TextButton(onClick = { onApprove(record) }, enabled = !busy, modifier = Modifier.testTag("memory_approve")) { Text("Approve") }
                        TextButton(onClick = { onReject(record) }, enabled = !busy, modifier = Modifier.testTag("memory_reject")) { Text("Reject") }
                    }
                }
                if (record.reviewStatus == MemoryReviewStatus.APPROVED)
                    TextButton(onClick = { onCorrect(record) }, enabled = !busy, modifier = Modifier.testTag("memory_correct")) { Text("Correct") }
                if (record.reviewStatus in setOf(MemoryReviewStatus.PENDING, MemoryReviewStatus.APPROVED))
                    TextButton(onClick = { onOrganize(record) }, enabled = !busy, modifier = Modifier.testTag("memory_organize")) { Text("Organize") }
                TextButton(onClick = { onDelete(record) }, enabled = !busy, modifier = Modifier.testTag("memory_delete")) { Text("Erase") }
            }
        }
    }
}

private fun MemorySource.sourceLabel(): String = when { eventSource.contains("voice", true) -> "Voice conversation"; eventSource.contains("manual", true) -> "Manual entry"; eventSource.contains("text", true) -> "Text conversation"; else -> eventSource }
