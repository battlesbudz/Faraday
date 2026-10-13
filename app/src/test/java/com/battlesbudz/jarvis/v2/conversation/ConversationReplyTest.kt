package com.battlesbudz.jarvis.v2.conversation

import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkCapture
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkOutcome
import com.battlesbudz.jarvis.v2.diagnostics.PipelineBenchmarkProvenance
import com.battlesbudz.jarvis.v2.diagnostics.TurnLatency
import com.battlesbudz.jarvis.v2.memory.MemoryDeliveryFence
import com.battlesbudz.jarvis.v2.memory.MemoryTurnContext
import org.junit.Assert.*
import org.junit.Test

class ConversationReplyTest {
    private class Fixture(ownsBenchmark: Boolean = true) {
        var nanos = 0L
        var epoch = 1L
        val fence = MemoryDeliveryFence()
        val queued = mutableListOf<() -> Unit>()
        val tokens = mutableListOf<String>()
        val completions = mutableListOf<String>()
        val answers = mutableListOf<String>()
        val latencies = mutableListOf<TurnLatency>()
        val saved = mutableListOf<Pair<PipelineBenchmarkOutcome, String?>>()
        val capture = PipelineBenchmarkCapture("reply", "text", 0,
            PipelineBenchmarkProvenance("test", 1), nowMs = { nanos / 1_000_000 })
        val reply = ConversationReply("reply", capture, ownsBenchmark, fence,
            post = { queued.add(it) }, onToken = tokens::add, onComplete = completions::add,
            onLatency = latencies::add, onAnswer = answers::add,
            finishOwnedBenchmark = { _, outcome, failure -> saved.add(outcome to failure) },
            nowNanos = { nanos })
        fun bind(expiresAtMs: Long? = null) {
            val context = MemoryTurnContext("approved evidence", "token", "question", expiresAtMs,
                epoch, currentEpoch = { epoch })
            reply.bindMemory(context, fence.ticket(expiresAtMs))
        }
        fun drain() { queued.toList().also { queued.clear() }.forEach { it() } }
    }

    @Test fun queuedMemoryAnswerIsFencedAfterMutationAndOwnerStillCompletes() {
        val f = Fixture()
        f.bind()
        f.reply.postToken("old fact")
        f.reply.postFinish("old answer")
        f.fence.invalidate()
        f.drain()
        assertTrue(f.tokens.isEmpty())
        assertTrue(f.answers.isEmpty())
        assertTrue(f.latencies.isEmpty())
        assertEquals(listOf("Memory changed while I was responding. Please ask again."), f.completions)
    }

    @Test fun changedSnapshotEpochDropsAnswerEvenWithoutFenceMutation() {
        val f = Fixture()
        f.bind()
        f.reply.postFinish("old answer")
        f.epoch++
        f.drain()
        assertTrue(f.answers.isEmpty())
        assertEquals(1, f.completions.size)
        assertTrue(f.completions.single().startsWith("Memory changed"))
    }

    @Test fun expiredMemoryTicketCannotPublishAQueuedToken() {
        val f = Fixture()
        f.bind(System.currentTimeMillis() - 1)
        f.reply.postToken("expired fact")
        f.drain()
        assertTrue(f.tokens.isEmpty())
    }

    @Test fun unboundTerminalReplyPublishesAndMeasuresFirstVisibleTextAtDelivery() {
        val f = Fixture()
        f.reply.postToken(" ")
        f.nanos = 12_000_000
        f.drain()
        f.reply.postFinish("safe terminal")
        f.nanos = 30_000_000
        f.drain()
        assertEquals(listOf("safe terminal"), f.answers)
        assertEquals(f.answers, f.completions)
        assertEquals(30L, f.latencies.single().firstVisibleTextMs)
        assertEquals(30L, f.latencies.single().totalMs)
    }

    @Test fun voiceOwnedCaptureReceivesOutcomeButIsNotFinishedByTheReply() {
        val f = Fixture(ownsBenchmark = false)
        f.reply.recordOutcome(PipelineBenchmarkOutcome.CANCELLED, "cancelled")
        f.reply.finishBenchmark()
        assertTrue(f.saved.isEmpty())
        val result = f.capture.finish(PipelineBenchmarkOutcome.COMPLETE)
        assertEquals(PipelineBenchmarkOutcome.CANCELLED, result!!.outcome)
        assertEquals("cancelled", result.failureCode)
    }

    @Test fun replyOwnedCaptureFinalizesWithFailureAndExactProcessingDuration() {
        val f = Fixture()
        f.nanos = 55_000_000
        f.reply.recordOutcome(PipelineBenchmarkOutcome.REJECTED, "busy")
        f.reply.finishBenchmark()
        assertEquals(listOf(PipelineBenchmarkOutcome.REJECTED to "busy"), f.saved)
        assertEquals(55.0, f.capture.finish(PipelineBenchmarkOutcome.REJECTED)!!.observedMetrics["reply_processing_ms"])
    }
}
