package com.battlesbudz.jarvis.v2.memory
import org.junit.Assert.*
import org.junit.Test
class MemoryDeliveryFenceTest {
 @Test fun invalidatedQueuedPublicationNeverReachesItsCallback() {
  val fence=MemoryDeliveryFence(); val ticket=fence.ticket(); var published=false
  fence.invalidate(); assertFalse(fence.publish(ticket) { published=true }); assertFalse(published)
 }
 @Test fun currentTicketPublishesExactlyOnceAtBoundary() {
  val fence=MemoryDeliveryFence(); var published=0; assertTrue(fence.publish(fence.ticket()) { published++ }); assertEquals(1,published)
 }
 @Test fun expiryInvalidatesQueuedPublicationWithoutMutationObserver() {
  val fence=MemoryDeliveryFence(); val ticket=fence.ticket(expiresAtMs = 10L); var published=false
  assertFalse(fence.isValid(ticket, nowMs = 10L))
  assertFalse(fence.publish(ticket) { published=true })
  assertFalse(published)
 }

 @Test fun publicationAndMutationAreSerializedAtTheActualCallbackBoundary() {
  val fence = MemoryDeliveryFence(); val ticket = fence.ticket()
  val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
  val invalidated = java.util.concurrent.atomic.AtomicBoolean(false)
  val publisher = Thread {
   assertTrue(fence.publish(ticket) { entered.countDown(); release.await() })
  }
  publisher.start(); assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
  val mutation = Thread { fence.invalidate(); invalidated.set(true) }
  mutation.start()
  Thread.sleep(20)
  assertFalse("invalidation must wait until the callback has crossed the serialized gate", invalidated.get())
  release.countDown(); publisher.join(1_000); mutation.join(1_000)
  assertTrue(invalidated.get()); assertFalse(fence.publish(ticket) {})
 }

 @Test fun durableMemoryTransitionWritesCutoffThenSummaryThenToken() {
  val writes = mutableListOf<String>()
  assertTrue(MemoryContextPersistence.adopt("old", "new",
   persistCutoff = { writes += "cutoff"; true },
   clearSummary = { writes += "summary"; true },
   persistToken = { writes += "token:$it"; true }))
  assertEquals(listOf("cutoff", "summary", "token:new"), writes)
 }
 @Test fun durableMemoryTransitionFailsClosedWithoutPublishingANewerToken() {
  listOf("cutoff", "summary", "token").forEach { failedStep ->
   var persistedToken = "old"
   val writes = mutableListOf<String>()
   try {
    MemoryContextPersistence.adopt("old", "new",
     persistCutoff = { writes += "cutoff"; failedStep != "cutoff" },
     clearSummary = { writes += "summary"; failedStep != "summary" },
     persistToken = { writes += "token"; if (failedStep == "token") false else { persistedToken = it!!; true } })
    fail("expected durable $failedStep failure")
   } catch (_: IllegalStateException) { }
   assertEquals("old", persistedToken)
   when (failedStep) {
    "cutoff" -> assertEquals(listOf("cutoff"), writes)
    "summary" -> assertEquals(listOf("cutoff", "summary"), writes)
    else -> assertEquals(listOf("cutoff", "summary", "token"), writes)
   }
  }
 }
 @Test fun pendingBoundaryMakesAnAlreadyReadContextFailBeforeDurablePublication() {
  var epoch = 5L; var pending = false
  val context = MemoryTurnContext("packet", "token", "name", null, epoch) {
   if (pending) Long.MIN_VALUE else epoch
  }
  assertTrue(context.isCurrent()); pending = true; assertFalse(context.isCurrent())
  pending = false; epoch++; assertFalse(context.isCurrent())
 }

 @Test fun stalePrecheckCannotPublishAfterMutationAtTheSerializedGate() {
  val fence = MemoryDeliveryFence(); val ticket = fence.ticket(); var sink = 0
  val observed = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
  val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
  val worker = Thread {
   try {
    assertTrue(fence.isValid(ticket)) // Mimics an asynchronous callback's stale preliminary check.
    observed.countDown(); assertTrue(release.await(1, java.util.concurrent.TimeUnit.SECONDS))
    fence.publish(ticket) { sink++ }
   } catch (t: Throwable) { failure.set(t) }
  }
  worker.start(); assertTrue(observed.await(1, java.util.concurrent.TimeUnit.SECONDS))
  fence.invalidate(); release.countDown(); worker.join(1_000)
  failure.get()?.let { throw AssertionError("worker failed", it) }
  assertEquals(0, sink)
 }

 @Test fun crashAtEachDurableBoundaryNeverReopensWithANewTokenAndOldResidentContext() {
  listOf("cutoff", "summary", "token").forEach { crashAfter ->
   var persistedToken = "old"; var cutoff = false; var summary = "old summary"
   try {
    MemoryContextPersistence.adopt("old", "new",
     persistCutoff = { cutoff = true; crashAfter != "cutoff" },
     clearSummary = { summary = ""; crashAfter != "summary" },
     persistToken = { persistedToken = it!!; crashAfter != "token" })
    fail("expected simulated crash at $crashAfter")
   } catch (_: IllegalStateException) { }
   // A restart sees either the old token (and must fence/reconcile) or a fully published
   // resident boundary. The helper never exposes a new token before cutoff and summary persist.
   assertTrue(persistedToken != "new" || (cutoff && summary.isEmpty()))
   MemoryContextPersistence.adopt(persistedToken, "new",
    persistCutoff = { cutoff = true; true }, clearSummary = { summary = ""; true },
    persistToken = { persistedToken = it!!; true })
   assertEquals("new", persistedToken); assertTrue(cutoff); assertEquals("", summary)
  }
 }

 @Test fun releasedBoundAnswerStillRejectsQueuedFinalCallbackAfterMutation() {
  val fence = MemoryDeliveryFence(); val guard = MemoryPublicationGuard(fence); val ticket = fence.ticket()
  guard.bind(ticket) { true } // Answer resources may now release; publication authority remains immutable.
  fence.invalidate(); var transcriptWrites = 0
  assertFalse(guard.publish { transcriptWrites++ })
  assertEquals(0, transcriptWrites)
 }

 @Test fun releasedBoundAnswerCannotPublishIntoAReplacementCall() {
  val fence = MemoryDeliveryFence(); val guard = MemoryPublicationGuard(fence); var liveCall = "first"
  guard.bind(fence.ticket()) { liveCall == "first" }
  liveCall = "replacement"; var transcriptWrites = 0
  assertFalse(guard.publish { transcriptWrites++ })
  assertEquals(0, transcriptWrites)
 }

}
