package com.battlesbudz.jarvis.v2.ui

import android.app.Activity
import android.content.ContentResolver
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkReport
import kotlinx.coroutines.*
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

private const val SENTINEL = "old bytes must be replaced"
private class ManualDispatcher : CoroutineDispatcher() {
    val queue = LinkedBlockingQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() { while (true) (queue.poll() ?: return).run() }
}
private class NoopApplier : AbstractApplier<Unit>(Unit) {
    override fun insertBottomUp(index: Int, instance: Unit) {}
    override fun insertTopDown(index: Int, instance: Unit) {}
    override fun move(from: Int, to: Int, count: Int) {}
    override fun remove(index: Int, count: Int) {}
    override fun onClear() {}
}
private class Sink(val beforeTruncate: Boolean = false, val afterTruncate: Boolean = false) {
    @Volatile var text = SENTINEL
    val started = CountDownLatch(1)
    val release = CountDownLatch(if (beforeTruncate || afterTruncate) 1 else 0)
    val written = CountDownLatch(1)
    val opens = AtomicInteger()
    fun open(): OutputStream {
        opens.incrementAndGet()
        if (!beforeTruncate) text = ""
        started.countDown()
        check(release.await(5, TimeUnit.SECONDS)) { "IO barrier timed out" }
        if (beforeTruncate) text = ""
        return object : OutputStream() {
            override fun write(value: Int) { error("bulk UTF-8 write expected") }
            override fun write(bytes: ByteArray) { text += bytes.toString(Charsets.UTF_8); written.countDown() }
        }
    }
}
private class Host : AutoCloseable {
    val dispatcher = ManualDispatcher()
    val frame = BroadcastFrameClock()
    val recomposer = Recomposer(dispatcher + frame)
    val runner = CoroutineScope(dispatcher + frame).launch { recomposer.runRecomposeAndApplyChanges() }
    val composition = Composition(NoopApplier(), recomposer)
    val sinks = mutableMapOf<String, Sink>()
    val context = object : ContextWrapper(null) {
        private val resolver = ContentResolver { uri, mode ->
            check(mode == "wt")
            checkNotNull(sinks[uri.toString()]).open()
        }
        override fun getContentResolver(): ContentResolver = resolver
    }
    val pending = mutableListOf<Int>()
    val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            check(contract.createIntent(context, input).action == Intent.ACTION_CREATE_DOCUMENT)
            pending += requestCode
        }
    }
    val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    val statuses = mutableListOf<String>()
    var state: BenchmarkExportState? = null
    var committed = 0
    var committedEpoch = 0L
    val disposed = mutableListOf<Int>()
    private var content by mutableStateOf<@Composable () -> Unit>({})
    private var generation = 0
    init {
        composition.setContent { content() }
        replace(1)
        await { committed == 1 }
    }
    fun replace(reportEpoch: Long): Int {
        val next = ++generation
        update(next, reportEpoch)
        return next
    }
    fun refresh(reportEpoch: Long) { update(generation, reportEpoch) }
    private fun update(next: Int, reportEpoch: Long) {
        val report = PipelineBenchmarkReport(emptyList(), reportEpoch)
        content = {
            key(next) {
                DisposableEffect(next) { onDispose { disposed += next } }
                CompositionLocalProvider(LocalContext provides context, LocalActivityResultRegistryOwner provides owner) {
                    val current = rememberBenchmarkExport(report, null, "same-scope", statuses::add)
                    SideEffect { state = current; committed = next; committedEpoch = reportEpoch }
                }
            }
        }
        Snapshot.sendApplyNotifications()
    }
    fun await(observed: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!observed()) {
            Snapshot.sendApplyNotifications()
            dispatcher.drain()
            if (frame.hasAwaiters) { frame.sendFrame(System.nanoTime()); dispatcher.drain() }
            if (!observed()) {
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) { "condition timed out; committed=$committed pending=${pending.size} statuses=$statuses" }
                dispatcher.queue.poll(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)?.run()
            }
        }
    }
    fun start(): Int {
        val count = pending.size
        checkNotNull(state).export(BenchmarkExportFormat.TXT, "save")
        await { pending.size == count + 1 }
        return pending.last()
    }
    fun uri(name: String, sink: Sink = Sink()): Uri = Uri.parse("content://host/$name").also { sinks[it.toString()] = sink }
    fun deliver(code: Int, uri: Uri?) {
        check(registry.dispatchResult(code, if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
            uri?.let { Intent().setData(it) }))
    }
    override fun close() {
        sinks.values.forEach { it.release.countDown() }
        composition.dispose(); runner.cancel(); recomposer.cancel(); dispatcher.drain()
    }
}
private var cases = 0
private fun case(name: String, run: () -> Unit) { run(); cases++; println("PASS $name") }
fun main() {
    case("queued content replacement leaves old callback admitted before commit; delayed truncate reproduces 16KB second assertion") {
        Host().use { h ->
            val old = Sink(beforeTruncate = true); val oldUri = h.uri("old", old); val request = h.start()
            val generation = h.replace(2)
            check(h.committed == 1 && h.disposed.isEmpty())
            h.deliver(request, oldUri)
            h.dispatcher.drain() // intentionally withhold the frame, exactly the missing fixture boundary
            check(old.started.await(5, TimeUnit.SECONDS))
            check(old.text == SENTINEL)
            h.await { h.committed == generation }; check(1 in h.disposed)
            val fresh = Sink(); val freshUri = h.uri("fresh", fresh); h.deliver(h.start(), freshUri)
            h.await { !h.state!!.busy }; check(fresh.text.contains("exportedAtEpochMs=2"))
            old.release.countDown(); check(old.written.await(5, TimeUnit.SECONDS))
            check(old.text.contains("exportedAtEpochMs=1"))
            check(!old.text.contains("exportedAtEpochMs=2"))
        }
    }
    case("callback admitted before commit can truncate synchronously; blocked write reproduces Fold first assertion") {
        Host().use { h ->
            val old = Sink(afterTruncate = true); val uri = h.uri("old", old); val request = h.start()
            val generation = h.replace(2); h.deliver(request, uri); h.dispatcher.drain()
            check(old.started.await(5, TimeUnit.SECONDS)); check(old.text == "")
            h.await { h.committed == generation }; old.release.countDown()
            check(old.written.await(5, TimeUnit.SECONDS)); check(old.text.contains("exportedAtEpochMs=1"))
        }
    }
    case("committed replacement drops old raw registry result while fresh same-scope payload remains pending, repeated 8 times") {
        Host().use { h ->
            repeat(8) { index ->
                val old = Sink(); val stale = h.uri("old-$index", old); val oldRequest = h.start()
                val generation = h.replace((index + 2).toLong()); h.await { h.committed == generation }
                check(generation - 1 in h.disposed)
                val fresh = Sink(); val target = h.uri("fresh-$index", fresh); val freshRequest = h.start()
                val payload = h.state!!.pendingSave
                h.deliver(oldRequest, stale); h.dispatcher.drain()
                check(old.text == SENTINEL && old.opens.get() == 0)
                check(h.state!!.busy && h.state!!.pendingSave == payload)
                check(!h.state!!.tryBegin())
                h.deliver(freshRequest, target); h.await { !h.state!!.busy }
                check(fresh.text == payload && fresh.text.contains("Scope: same-scope"))
                check(fresh.text.contains("exportedAtEpochMs=${index + 2}"))
                check(old.text == SENTINEL && old.opens.get() == 0)
            }
        }
    }
    case("disposal cancels callback coroutine queued before IO entry") {
        Host().use { h ->
            val sink = Sink(); val target = h.uri("cancel-before-io", sink); val request = h.start()
            h.deliver(request, target)
            h.composition.dispose() // callback queued its coroutine, but no host dispatcher turn ran it
            h.dispatcher.drain()
            check(sink.opens.get() == 0 && sink.text == SENTINEL)
        }
    }
    case("disposal cancels a save dispatched to IO but still queued before provider entry") {
        check(System.getProperty("kotlinx.coroutines.io.parallelism") == "2")
        Host().use { h ->
            val sink = Sink(); val target = h.uri("cancel-queued-io", sink); val request = h.start()
            val occupied = CountDownLatch(2); val release = CountDownLatch(1)
            val workers = List(2) { CoroutineScope(Dispatchers.IO).launch {
                occupied.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            var ownerJobs: List<Job> = emptyList()
            try {
                check(occupied.await(5, TimeUnit.SECONDS))
                h.deliver(request, target); h.dispatcher.drain()
                check(sink.opens.get() == 0)
                ownerJobs = h.recomposer.effectCoroutineContext[Job]!!.children.toList()
                check(ownerJobs.isNotEmpty())
                h.composition.dispose()
            } finally { release.countDown() }
            h.await { ownerJobs.all { it.isCompleted } }
            runBlocking { withTimeout(5_000) {
                workers.forEach { it.join() }; ownerJobs.forEach { it.join() }
            } }
            check(sink.opens.get() == 0 && sink.text == SENTINEL)
        }
    }
    case("canceled picker clears pending state; live same-scope export retains its frozen report") {
        Host().use { h ->
            val first = h.start(); h.deliver(first, null); check(!h.state!!.busy && h.state!!.pendingSave == null)
            val sink = Sink(); val target = h.uri("live", sink); val second = h.start()
            val frozen = h.state!!.pendingSave; check(frozen!!.contains("exportedAtEpochMs=1"))
            h.refresh(99); h.await { h.committedEpoch == 99L }
            check(h.state!!.busy && h.state!!.pendingSave == frozen)
            h.deliver(second, target); h.await { !h.state!!.busy }
            check(sink.text == frozen && sink.opens.get() == 1)
        }
    }
    println("$cases deterministic lifecycle cases passed with exact production composable/transport source, real Compose 1.7.6 / Activity 1.10.0; native provider I/O and Android tracing replaced by explicit host seams")
}
