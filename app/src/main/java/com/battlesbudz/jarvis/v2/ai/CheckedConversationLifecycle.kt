package com.battlesbudz.jarvis.v2.ai

/**
 * Owns a native engine and its exact Conversation child. A callback terminal is only an event;
 * only a successful checked drain establishes that native borrowers have returned.
 *
 * Native calls never run while [lock] is held: the last native callback may inspect our state.
 * Concurrent lifecycle operations fail closed rather than waiting on the UI/callback thread.
 */
internal class CheckedConversationLifecycle<C : Any>(
    private val createConversation: () -> C,
    private val cancelConversation: (C) -> Unit,
    private val awaitIdle: (C) -> Unit,
    private val closeConversation: (C) -> Unit,
    private val closeEngine: () -> Unit,
    private val checkWorkerThread: () -> Unit,
    private val onQuarantined: (Throwable) -> Unit = {}
) {
    class Turn<C : Any> internal constructor(internal val conversation: C)
    class ChildLease<T : Any> internal constructor(private val checkedClose: (T) -> Unit) {
        private var ownedChild: T? = null
        val child: T get() = checkNotNull(ownedChild) { "Native child has not been adopted" }
        internal val hasChild: Boolean get() = ownedChild != null
        internal var released = false
        // Holder and close function already exist before the native allocation returns.
        internal fun adopt(child: T) { ownedChild = child }
        internal fun dispose() = checkedClose(child)
    }

    private val lock = Any()
    private var conversation: C? = null
    private var preparedUnused = false
    private var activeTurn: Turn<C>? = null
    private var childLease: ChildLease<*>? = null
    private var childClosing = false
    private var turnFinishing = false
    private var busy = false
    private var initialized = false
    private var closed = false
    private var closeRequested = false
    private var quarantine: Throwable? = null
    private var needsHistoryRebuild = false

    val hasConversationHistory: Boolean get() = synchronized(lock) { conversation != null && !preparedUnused }
    val hasUnusedPreparedConversation: Boolean get() = synchronized(lock) { preparedUnused && conversation != null && !busy && quarantine == null }

    val safeToRelease: Boolean get() = synchronized(lock) { !busy && quarantine == null }
    val isQuarantined: Boolean get() = synchronized(lock) { quarantine != null }
    /**
     * True once a close has been requested, whether it completed or is
     * retained by a failed drain. A successfully closed engine also reports
     * [safeToRelease], so owners that must not touch a closing/closed engine
     * (for example an optional post-answer reset) need this separate signal.
     */
    val isClosed: Boolean get() = synchronized(lock) { closed || closeRequested }
    val requiresConfirmedHistoryRebuild: Boolean get() = synchronized(lock) { needsHistoryRebuild }

    fun initialize(initializeEngine: () -> Unit) {
        checkWorkerThread()
        synchronized(lock) {
            checkAvailable()
            check(!initialized) { "Native engine is already initialized" }
            busy = true
        }
        try {
            initializeEngine()
            synchronized(lock) { initialized = true }
        } finally { synchronized(lock) { busy = false } }
    }

    /** Prepare one idle child under the existing exclusive owner; submit no user turn. */
    fun prepareConversation(isCurrent: () -> Boolean): Boolean {
        checkWorkerThread()
        if (!isCurrent()) return false
        synchronized(lock) {
            checkAvailable()
            check(initialized) { "Native engine must be initialized before preparation" }
            check(!needsHistoryRebuild) { "Failed native history must be rebuilt before preparation" }
            if (conversation != null) return preparedUnused
            busy = true
        }
        try {
            val child = createConversation()
            synchronized(lock) { conversation = child }
            awaitIdle(child)
            if (!isCurrent()) { disposeConversation(child); return false }
            synchronized(lock) { preparedUnused = true }
            return true
        } catch (error: Throwable) {
            synchronized(lock) { needsHistoryRebuild = true }
            if (synchronized(lock) { conversation != null }) quarantine(error)
            throw error
        } finally { synchronized(lock) { busy = false } }
    }

    fun beginTurn(): Turn<C> {
        checkWorkerThread()
        val current = synchronized(lock) {
            checkAvailable()
            check(initialized) { "Native engine must be initialized before generation" }
            check(!needsHistoryRebuild) { "Failed native history must be rebuilt from confirmed history" }
            busy = true
            conversation
        }
        try {
            val child = current ?: createConversation()
            return synchronized(lock) {
                conversation = child
                preparedUnused = false
                Turn(child).also { activeTurn = it }
            }
        } catch (error: Throwable) {
            synchronized(lock) { busy = false; needsHistoryRebuild = true }
            throw error
        }
    }

    /** Raw incremental Session is a separate child of the same engine and exclusive model lease. */
    fun <T : Any> createChild(
        create: () -> T,
        allocateLease: ((T) -> Unit) -> ChildLease<T> = { ChildLease(it) },
        checkedClose: (T) -> Unit
    ): ChildLease<T> {
        checkWorkerThread()
        // Allocate the holder and cleanup function before creating a native handle. The factory
        // seam allows deterministic allocation-failure tests without exhausting the JVM heap.
        val lease = allocateLease(checkedClose)
        synchronized(lock) {
            checkAvailable()
            check(initialized) { "Native engine must be initialized before child creation" }
            check(conversation == null) { "Reset the Conversation before allocating a Session" }
            busy = true
            childLease = lease
        }
        try {
            lease.adopt(create())
            return lease
        } catch (error: Throwable) {
            try {
                if (lease.hasChild) quarantine(error)
                else synchronized(lock) { if (childLease === lease) childLease = null }
            } finally { synchronized(lock) { busy = false } }
            throw error
        }
    }

    fun quarantineChild(lease: ChildLease<*>, error: Throwable) {
        synchronized(lock) {
            check(childLease === lease && !lease.released) { "Not the active native child" }
        }
        try { quarantine(error) } finally { synchronized(lock) { busy = false } }
    }

    fun closeChild(lease: ChildLease<*>) {
        checkWorkerThread()
        synchronized(lock) {
            if (lease.released) return
            check(childLease === lease && !childClosing) { "Not the active native child or already closing" }
            checkNotQuarantined()
            childClosing = true
        }
        try { disposeChild(lease) }
        catch (error: Throwable) { quarantine(error); throw error }
        finally { synchronized(lock) { childClosing = false; busy = false } }
    }

    /**
     * Always drain, including successful callback termination and synchronous send rejection.
     * [successful] is sampled again after blocking drain, so cancellation during drain discards
     * history too. A failed turn's handle is never reused, even if cancellation/drain succeeded.
     */
    fun finishTurn(turn: Turn<C>, successful: () -> Boolean, onDrained: (C) -> Unit = {}): Boolean {
        checkWorkerThread()
        synchronized(lock) {
            check(busy && activeTurn === turn && !turnFinishing) { "Not the active native turn or already draining" }
            turnFinishing = true
        }
        var cancellationFailure: Throwable? = null
        try {
            if (!successful()) {
                synchronized(lock) { needsHistoryRebuild = true }
                try { cancelConversation(turn.conversation) }
                catch (error: Throwable) { cancellationFailure = error }
            }
            try { awaitIdle(turn.conversation) }
            catch (error: Throwable) {
                cancellationFailure?.takeUnless { it === error }?.let(error::addSuppressed)
                quarantine(error)
                throw error
            }
            if (successful() && cancellationFailure == null) {
                try { onDrained(turn.conversation) }
                catch (error: Throwable) { cancellationFailure = error }
            }
            val accepted = successful() && cancellationFailure == null
            if (!accepted) {
                synchronized(lock) { needsHistoryRebuild = true }
                disposeConversation(turn.conversation)
            }
            cancellationFailure?.let { throw it }
            return accepted
        } finally {
            synchronized(lock) { activeTurn = null; turnFinishing = false; busy = false }
        }
    }

    /** Caller must rebuild its next full prompt from confirmed history and executor receipts. */
    fun resetForConfirmedHistory() {
        checkWorkerThread()
        val child = acquireControl()
        try {
            child?.let { drainAndDispose(it) }
            synchronized(lock) { needsHistoryRebuild = false }
        } finally { synchronized(lock) { busy = false } }
    }

    /** Never retries a quarantined destructor implicitly; the owning lease must stay retained. */
    fun close() = closeInternal(retryQuarantine = false)

    /** Explicit owner-worker recovery. Retain all leases until this returns successfully. */
    fun retryQuarantinedClose() = closeInternal(retryQuarantine = true)

    private fun closeInternal(retryQuarantine: Boolean) {
        checkWorkerThread()
        val child = synchronized(lock) {
            if (closed) return
            closeRequested = true
            check(!busy) { "Native borrowers are still active; retain the engine and model lease" }
            if (!retryQuarantine) checkNotQuarantined()
            busy = true
            conversation to childLease
        }
        try {
            child.second?.let { disposeChild(it) }
            child.first?.let { drainAndDispose(it) }
            try { closeEngine() }
            catch (error: Throwable) { quarantine(error); throw error }
            synchronized(lock) { closed = true; quarantine = null }
        } finally { synchronized(lock) { busy = false } }
    }

    private fun acquireControl(): C? = synchronized(lock) {
        checkAvailable()
        busy = true
        conversation
    }

    private fun checkAvailable() {
        check(!closed && !closeRequested) { "Native engine is closing or closed" }
        check(!busy) { "Another native operation is still active" }
        checkNotQuarantined()
    }

    private fun checkNotQuarantined() {
        check(quarantine == null) { "Native drain/disposal failed; retain the engine and model lease" }
    }

    private fun drainAndDispose(child: C) {
        try { awaitIdle(child) }
        catch (error: Throwable) { quarantine(error); throw error }
        disposeConversation(child)
    }

    private fun disposeChild(lease: ChildLease<*>) {
        try { lease.dispose() }
        catch (error: Throwable) { quarantine(error); throw error }
        synchronized(lock) { lease.released = true; if (childLease === lease) childLease = null }
    }

    private fun disposeConversation(child: C) {
        try { closeConversation(child) }
        catch (error: Throwable) { quarantine(error); throw error }
        synchronized(lock) { if (conversation === child) { conversation = null; preparedUnused = false } }
    }

    private fun quarantine(error: Throwable) {
        val first = synchronized(lock) {
            val newlyQuarantined = quarantine == null
            quarantine = error
            needsHistoryRebuild = true
            newlyQuarantined
        }
        if (first) {
            try { onQuarantined(error) }
            catch (observerError: Throwable) {
                if (observerError !== error) error.addSuppressed(observerError)
            }
        }
    }
}
