@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.battlesbudz.jarvis.v2.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.battlesbudz.jarvis.v2.memory.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Local-first memory wiki. Pages are derived exclusively from approved active ledger entries. */
@Composable
internal fun MemoryScreen(
    memoryOs: MemoryOs,
    onBack: () -> Unit,
    callActive: Boolean = false,
    callStatus: String = "",
    onEndCall: (((String) -> Unit) -> Unit)? = null,
    onOpenChat: () -> Unit = onBack,
    onOpenVoice: () -> Unit = onBack,
) {
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(lifecycle.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var snapshot by remember { mutableStateOf<MemorySnapshot?>(null) }
    var storageError by remember { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var pageId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var highlightedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showEditor by rememberSaveable { mutableStateOf(false) }
    var correctionId by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var topic by rememberSaveable { mutableStateOf("") }
    var categoryName by rememberSaveable { mutableStateOf(WikiCategory.ABOUT_YOU.name) }
    var categoryOverridden by rememberSaveable { mutableStateOf(false) }
    var topicOverridden by rememberSaveable { mutableStateOf(false) }
    var sourceEventId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var sourceCreatedAtMs by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var organizeId by rememberSaveable { mutableStateOf<String?>(null) }
    var organizeRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var organizeTopic by rememberSaveable { mutableStateOf("") }
    var organizeCategoryName by rememberSaveable { mutableStateOf(WikiCategory.ABOUT_YOU.name) }
    var confirmDeleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDeleteRevision by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    var eraseGeneration by rememberSaveable { mutableStateOf<Long?>(null) }

    fun passiveRead() {
        scope.launch {
            try {
                val read = withContext(Dispatchers.IO) { memoryOs.read() }
                val incoming = read.snapshot
                val currentGeneration = snapshot?.generation
                if (incoming == null || currentGeneration == null || incoming.generation >= currentGeneration) snapshot = incoming
                storageError = read.error
                nowMs = System.currentTimeMillis()
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                snapshot = null
                storageError = t.message ?: "Memory manager could not reload saved memories."
            }
        }
    }
    fun mutate(action: () -> MemoryResult, done: () -> Unit = {}) {
        if (busy) return
        busy = true; actionError = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { action() }
                if (result.outcome in setOf(MemoryOutcome.CREATED, MemoryOutcome.APPROVED, MemoryOutcome.REJECTED, MemoryOutcome.DELETED, MemoryOutcome.ALREADY_RECORDED)) {
                    done(); notice = result.message
                    val read = withContext(Dispatchers.IO) { memoryOs.read() }
                    snapshot = read.snapshot; storageError = read.error; nowMs = System.currentTimeMillis()
                } else actionError = result.message
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                actionError = t.message ?: "Memory operation failed."
            } finally { busy = false }
        }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> started = event != Lifecycle.Event.ON_STOP && event != Lifecycle.Event.ON_DESTROY }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { passiveRead() }
    LaunchedEffect(started) {
        if (started) while (true) { delay(1_000); passiveRead() }
    }

    val index = snapshot?.let { MemoryWiki.build(it, nowMs) }
    val category = WikiCategory.entries.firstOrNull { it.name == categoryName } ?: WikiCategory.ABOUT_YOU
    val organizeCategory = WikiCategory.entries.firstOrNull { it.name == organizeCategoryName } ?: WikiCategory.ABOUT_YOU
    val correction = snapshot?.memories?.firstOrNull { it.id == correctionId }
    val organizeTarget = snapshot?.memories?.firstOrNull { it.id == organizeId }
    val deleteTarget = snapshot?.memories?.firstOrNull { it.id == confirmDeleteId }
    val selectedPage = index?.pages?.firstOrNull { it.id == pageId }
    val selectedDetail = snapshot?.memories?.firstOrNull { it.id == detailId }
    val pending = snapshot?.memories?.count { it.reviewStatus == MemoryReviewStatus.PENDING } ?: 0
    val searchRows = if (query.isBlank()) emptyList() else when (tab) {
        0 -> index?.search(query).orEmpty()
        1 -> snapshot?.memories.orEmpty().filter { it.reviewStatus == MemoryReviewStatus.PENDING && it.matches(query) }
        else -> snapshot?.memories.orEmpty().filter { it.matches(query) }
    }
    fun beginAdd() {
        correctionId = null; correctionRevision = null; draft = ""; sourceEventId = UUID.randomUUID().toString(); sourceCreatedAtMs = System.currentTimeMillis()
        val suggestion = MemoryWiki.suggest("New memory")
        categoryName = suggestion.category.name; topic = suggestion.topic; categoryOverridden = false; topicOverridden = false; actionError = null; showEditor = true
    }
    fun beginCorrection(record: MemoryRecord) {
        correctionId = record.id; correctionRevision = record.revision; draft = ""; sourceEventId = UUID.randomUUID().toString(); sourceCreatedAtMs = System.currentTimeMillis()
        val assignment = record.wikiAssignment ?: MemoryWiki.suggest(record)
        categoryName = assignment.category.name; topic = assignment.topic; categoryOverridden = true; topicOverridden = true; actionError = null; showEditor = true; detailId = null
    }
    fun openRecord(record: MemoryRecord) {
        val page = index?.pages?.firstOrNull { it.id.startsWith("topic:") && it.records.any { row -> row.id == record.id } }
            ?: index?.pages?.firstOrNull { it.records.any { row -> row.id == record.id } }
        pageId = page?.id; detailId = null; highlightedId = record.id; query = ""; tab = 0
    }
    fun beginOrganize(record: MemoryRecord) {
        val assignment = record.wikiAssignment ?: MemoryWiki.suggest(record)
        organizeId = record.id; organizeRevision = record.revision; organizeCategoryName = assignment.category.name; organizeTopic = assignment.topic; actionError = null
    }
    fun requestDelete(record: MemoryRecord) { confirmDeleteId = record.id; confirmDeleteRevision = record.revision }
    fun requestEraseAll() {
        val current = snapshot
        if (current == null) actionError = storageError ?: "Saved memories are unavailable."
        else { eraseGeneration = current.generation; confirmDeleteAll = true; actionError = null }
    }

    BackHandler(enabled = !busy) {
        when { detailId != null -> detailId = null; pageId != null -> { pageId = null; highlightedId = null }; else -> onBack() }
    }
    MaterialTheme(colorScheme = memoryColorScheme()) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Memory", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Row { TextButton(onClick = ::beginAdd, enabled = !busy, modifier = Modifier.testTag("memory_new")) { Text("Add") }; TextButton(onClick = onOpenChat, enabled = !busy, modifier = Modifier.testTag("memory_back")) { Text("Back") } }
            }
            if (callActive) ActiveCallStrip(callStatus, onOpenVoice, onEndCall, { notice = it })
            if (selectedPage == null && selectedDetail == null) OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, label = { Text(if (tab == 0) "Search approved memories" else "Search this list") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("memory_search_input"))
            storageError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp).testTag("memory_error")) }
            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp).testTag("memory_error_action")) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp).testTag("memory_notice")) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    selectedDetail != null -> MemoryDetail(selectedDetail, snapshot?.memories.orEmpty(), onBack = { detailId = null }, onCorrect = ::beginCorrection, onOrganize = ::beginOrganize, onDelete = ::requestDelete, onApprove = { mutate({ memoryOs.approve(it.id, it.revision) }) }, onReject = { mutate({ memoryOs.reject(it.id, it.revision) }) })
                    selectedPage != null -> BoxWithConstraints(Modifier.fillMaxSize()) {
                        val openPage: (String) -> Unit = { pageId = it; detailId = null; highlightedId = null }
                        if (maxWidth >= 600.dp) Row(Modifier.fillMaxSize()) {
                            LazyColumn(Modifier.width(196.dp).fillMaxHeight().testTag("memory_expanded_category_nav"), contentPadding = PaddingValues(8.dp)) {
                                items(index?.categoryPages.orEmpty(), key = { it.id }) { categoryPage -> TextButton(onClick = { openPage(categoryPage.id) }, modifier = Modifier.fillMaxWidth().testTag("memory_expanded_category_${categoryPage.category.id}")) { Text(categoryPage.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                            }
                            VerticalDivider()
                            Box(Modifier.weight(1f)) { MemoryArticle(selectedPage, index, snapshot?.memories.orEmpty(), onBack = { pageId = null; highlightedId = null }, onOpenPage = openPage, onOpenRecord = { detailId = it.id }, highlightedId = highlightedId) }
                        } else MemoryArticle(selectedPage, index, snapshot?.memories.orEmpty(), onBack = { pageId = null; highlightedId = null }, onOpenPage = openPage, onOpenRecord = { detailId = it.id }, highlightedId = highlightedId)
                    }
                    query.isNotBlank() -> SearchList(searchRows, tab, onClear = { query = "" }, onOpen = { record -> if (record.reviewStatus == MemoryReviewStatus.APPROVED && index?.findMemory(record.id) != null) openRecord(record) else { detailId = record.id; query = ""; tab = if (record.reviewStatus == MemoryReviewStatus.PENDING) 1 else 2 } })
                    else -> {
                        Column(Modifier.fillMaxSize()) {
                            TabRow(selectedTabIndex = tab, modifier = Modifier.padding(horizontal = 16.dp)) { Tab(tab == 0, { tab = 0 }, text = { Text("Wiki") }, modifier = Modifier.testTag("memory_wiki_tab")); Tab(tab == 1, { tab = 1 }, text = { Text("Review ($pending)") }, modifier = Modifier.testTag("memory_review_tab")); Tab(tab == 2, { tab = 2 }, text = { Text("History") }, modifier = Modifier.testTag("memory_history_tab")) }
                            when (tab) {
                                0 -> WikiHome(index, onOpenPage = { pageId = it }, onEraseAll = ::requestEraseAll)
                                1 -> ReviewList(snapshot?.memories.orEmpty().filter { it.reviewStatus == MemoryReviewStatus.PENDING }, busy, onApprove = { mutate({ memoryOs.approve(it.id, it.revision) }) }, onReject = { mutate({ memoryOs.reject(it.id, it.revision) }) }, onCorrect = ::beginCorrection, onOrganize = ::beginOrganize, onDelete = ::requestDelete, onOpen = { detailId = it.id })
                                else -> HistoryList(snapshot?.memories.orEmpty(), busy, onCorrect = ::beginCorrection, onOrganize = ::beginOrganize, onDelete = ::requestDelete, onOpen = { detailId = it.id }, onEraseAll = ::requestEraseAll)
                            }
                        }
                    }
                }
            }
            NavigationBar(modifier = Modifier.padding(horizontal = 16.dp)) {
                NavigationBarItem(selected = false, onClick = onOpenChat, icon = {}, label = { Text("Chat") }, modifier = Modifier.testTag("memory_nav_chat"))
                NavigationBarItem(selected = false, onClick = onOpenVoice, icon = {}, label = { Text("Voice") }, modifier = Modifier.testTag("memory_nav_voice"))
                NavigationBarItem(selected = true, onClick = {}, icon = {}, label = { Text("Memory") }, modifier = Modifier.testTag("memory_nav_memory"))
            }
        }
    }

    if (showEditor) MemoryEditorDialog(draft, topic, category, categoryOverridden, topicOverridden, correctionId != null, correction, actionError, busy,
        onDraft = { value -> draft = value; sourceEventId = UUID.randomUUID().toString(); sourceCreatedAtMs = System.currentTimeMillis(); if (!categoryOverridden || !topicOverridden) { val suggested = MemoryWiki.suggest(value); if (!categoryOverridden) categoryName = suggested.category.name; if (!topicOverridden) topic = suggested.topic } },
        onTopic = { topic = it; topicOverridden = true }, onCategory = { categoryName = it.name; categoryOverridden = true },
        onDismiss = { showEditor = false; correctionId = null; correctionRevision = null }, onSave = {
            mutate({ memoryOs.propose(MemoryProposal(content = draft, source = MemorySource(sourceEventId, "manual entry", sourceCreatedAtMs), wikiAssignment = MemoryWikiAssignment(category, topic), correctsMemoryId = correctionId, expectedTargetRevision = correctionRevision)) }) { showEditor = false; correctionId = null; correctionRevision = null }
        })
    organizeTarget?.let { record -> OrganizeDialog(record, organizeCategory, organizeTopic, actionError, busy, onCategory = { organizeCategoryName = it.name }, onTopic = { organizeTopic = it }, onDismiss = { organizeId = null; organizeRevision = null }, onSave = { mutate({ memoryOs.assignWiki(record.id, organizeRevision, MemoryWikiAssignment(organizeCategory, organizeTopic)) }) { organizeId = null; organizeRevision = null } }) }
    deleteTarget?.let { record -> AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { if (!busy) confirmDeleteId = null }, title = { Text("Erase this memory?") }, text = { Column { Text("This also erases linked corrections."); actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory_error")) } } }, confirmButton = { TextButton(onClick = { mutate({ memoryOs.delete(record.id, confirmDeleteRevision) }) { confirmDeleteId = null } }, enabled = !busy, modifier = Modifier.testTag("memory_delete_confirm")) { Text("Erase") } }, dismissButton = { TextButton(onClick = { confirmDeleteId = null }, enabled = !busy, modifier = Modifier.testTag("memory_delete_cancel")) { Text("Cancel") } }) }
    if (confirmDeleteAll) AlertDialog(modifier = Modifier.semantics { testTagsAsResourceId = true }, onDismissRequest = { if (!busy) confirmDeleteAll = false }, title = { Text("Erase all memories?") }, text = { Column { Text("This permanently erases every saved memory and correction."); actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory_error")) } } }, confirmButton = { TextButton(onClick = { val generation = eraseGeneration; if (generation == null) actionError = "Saved memories are unavailable." else mutate({ memoryOs.deleteAll(generation) }) { confirmDeleteAll = false; eraseGeneration = null; pageId = null; detailId = null } }, enabled = !busy && eraseGeneration != null, modifier = Modifier.testTag("memory_delete_all_confirm")) { Text("Erase all") } }, dismissButton = { TextButton(onClick = { confirmDeleteAll = false; eraseGeneration = null }, enabled = !busy) { Text("Cancel") } })
    }
}

@Composable private fun ActiveCallStrip(status: String, onReturn: () -> Unit, onEnd: (((String) -> Unit) -> Unit)?, notice: (String) -> Unit) = Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text(if (status.isBlank()) "Voice call active" else "Voice call active · $status", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("voice_call_status")); TextButton(onClick = onReturn, modifier = Modifier.testTag("memory_call_return")) { Text("Return") }; TextButton(onClick = { onEnd?.invoke(notice) }, modifier = Modifier.testTag("voice_call_end")) { Text("End") } }

@Composable
private fun MemoryEditorDialog(draft: String, topic: String, category: WikiCategory, categoryOverridden: Boolean, topicOverridden: Boolean, isCorrection: Boolean, correction: MemoryRecord?, error: String?, busy: Boolean, onDraft: (String) -> Unit, onTopic: (String) -> Unit, onCategory: (WikiCategory) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) = AlertDialog(
    modifier = Modifier.semantics { testTagsAsResourceId = true },
    onDismissRequest = onDismiss,
    title = { Text(if (isCorrection) "Correct memory" else "Add a memory") },
    text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(draft, onDraft, label = { Text("What should be remembered?") }, modifier = Modifier.fillMaxWidth().testTag("memory_new_content"), maxLines = 5)
        Text("Category", style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("memory_category_picker")) { items(WikiCategory.entries, key = { it.name }) { item -> FilterChip(selected = item == category, onClick = { onCategory(item) }, label = { Text(item.title) }, modifier = Modifier.testTag("memory_category_${item.id}")) } }
        OutlinedTextField(topic, onTopic, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth().testTag("memory_topic_input"))
        if (!categoryOverridden || !topicOverridden) Text("Category and topic are suggested from your wording; you can choose either.", style = MaterialTheme.typography.bodySmall)
        if (correction != null) Text("Replaces: ${correction.content}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        else if (isCorrection) Text("The original memory will be checked when this correction is saved.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory_error")) }
    } },
    confirmButton = { TextButton(onClick = onSave, enabled = draft.isNotBlank() && topic.isNotBlank() && !busy, modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("memory_propose")) { Text(if (isCorrection) "Submit correction" else "Add for review") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
)

@Composable
private fun OrganizeDialog(record: MemoryRecord, category: WikiCategory, topic: String, error: String?, busy: Boolean, onCategory: (WikiCategory) -> Unit, onTopic: (String) -> Unit, onDismiss: () -> Unit, onSave: () -> Unit) = AlertDialog(
    modifier = Modifier.semantics { testTagsAsResourceId = true },
    onDismissRequest = onDismiss,
    title = { Text("Organize memory") },
    text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(record.content, maxLines = 3, overflow = TextOverflow.Ellipsis)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("memory_organize_category_picker")) { items(WikiCategory.entries, key = { it.name }) { item -> FilterChip(selected = item == category, onClick = { onCategory(item) }, label = { Text(item.title) }, modifier = Modifier.testTag("memory_organize_category_${item.id}")) } }
        OutlinedTextField(topic, onTopic, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth().testTag("memory_organize_topic"))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("memory_error")) }
    } },
    confirmButton = { TextButton(onClick = onSave, enabled = topic.isNotBlank() && !busy, modifier = Modifier.testTag("memory_assign")) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
)

@Composable private fun WikiHome(index: MemoryWikiIndex?, onOpenPage: (String) -> Unit, onEraseAll: () -> Unit) = LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { item { Column { Text("Your personal wiki", style = MaterialTheme.typography.headlineSmall); Text("Organized from approved memories", style = MaterialTheme.typography.bodySmall) } }; if (index == null) item { Text("Loading saved memories…") } else { items(index.categoryPages, key = { it.id }) { page -> Card(Modifier.fillMaxWidth().testTag("memory_category_row_${page.category.id}").clickable { onOpenPage(page.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Row(Modifier.padding(16.dp)) { Text(page.title.take(1), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp)); Text(page.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text("${page.records.size} ›") } } }; val recent = index.pages.filter { it.id.startsWith("topic:") && it.records.isNotEmpty() }.sortedByDescending { it.updatedAtMs }.take(6); if (recent.isNotEmpty()) { item { Text("Recently updated", style = MaterialTheme.typography.titleMedium) }; items(recent, key = { "recent:${it.id}" }) { page -> TextButton(onClick = { onOpenPage(page.id) }, modifier = Modifier.fillMaxWidth().testTag("memory_topic_row_${page.id}")) { Text(page.title, Modifier.weight(1f)); Text(dateLabel(page.updatedAtMs)) } } }; if (index.memories.isNotEmpty()) item { TextButton(onClick = onEraseAll, modifier = Modifier.testTag("memory_erase_all")) { Text("Erase all memories") } } } }

@Composable private fun SearchList(rows: List<MemoryRecord>, tab: Int, onClear: () -> Unit, onOpen: (MemoryRecord) -> Unit) = LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { item { TextButton(onClick = onClear, modifier = Modifier.testTag("memory_search")) { Text("Clear search") } }; if (rows.isEmpty()) item { Text(if (tab == 0) "No approved memories match that search." else "No memories match that search.") }; items(rows, key = { it.id }) { record -> MemorySearchCard(record, onClick = { onOpen(record) }) } }
@Composable private fun MemorySearchCard(record: MemoryRecord, onClick: () -> Unit) = Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("memory_search_result_${record.id}")) { Column(Modifier.padding(14.dp)) { Text(record.content); Text(record.placement().second, style = MaterialTheme.typography.labelSmall); Text("Captured from ${record.source.screenSourceLabel()} · ${dateLabel(record.source.createdAtMs)}", style = MaterialTheme.typography.labelSmall) } }

private fun MemoryRecord.matches(query: String): Boolean { val needle = query.trim().lowercase(); val placement = placement(); return listOf(content, source.eventSource, placement.first.title, placement.second, source.provenance.joinToString { it.label ?: it.kind }).any { needle in it.lowercase() } }
internal fun MemoryRecord.placement(): Pair<WikiCategory, String> { val a = wikiAssignment ?: MemoryWiki.suggest(this); return a.category to a.topic }
private fun MemorySource.screenSourceLabel(): String = when { eventSource.contains("voice", true) -> "Voice conversation"; eventSource.contains("manual", true) -> "Manual entry"; eventSource.contains("text", true) -> "Text conversation"; else -> eventSource }
private fun memoryColorScheme() = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF67E8F9), secondary = androidx.compose.ui.graphics.Color(0xFF22D3EE), background = androidx.compose.ui.graphics.Color(0xFF101316), surface = androidx.compose.ui.graphics.Color(0xFF171B1F), surfaceVariant = androidx.compose.ui.graphics.Color(0xFF222A30))
internal fun dateLabel(time: Long): String = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(time))
