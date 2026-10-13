package com.battlesbudz.jarvis.v2.voice

/** Serializes our recorder lifecycle with priority requests and recording snapshots. */
object MicrophoneHandoff {
    private val requested = java.util.concurrent.atomic.AtomicBoolean(false)
    val dictationRequested: Boolean get() = requested.get()
    val backgroundRecorders = java.util.concurrent.atomic.AtomicInteger(0)
    val keyboardHelperConnected = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val recorderLock = Any()
    private data class Recorder(val recording: () -> Boolean, val stop: () -> Unit)
    private val recorders = mutableMapOf<Any, Recorder>()
    private var pendingRequest = false
    private var refresh: (() -> Unit)? = null
    private val events = java.util.ArrayDeque<String>()
    val interrupted = kotlinx.coroutines.flow.MutableStateFlow(false)
    @Volatile var ownRecorderSilenced = false
    val shouldYield: Boolean get() = dictationRequested || interrupted.value

    fun <T> withRecorderLock(block: () -> T): T = synchronized(recorderLock, block)
    fun registerRecorder(key: Any, recording: () -> Boolean, stop: () -> Unit) = withRecorderLock {
        check(!shouldYield) { "Microphone priority changed before capture started." }
        recorders[key] = Recorder(recording, stop)
        backgroundRecorders.set(recorders.size)
    }
    fun unregisterRecorder(key: Any) = withRecorderLock {
        recorders.remove(key)
        backgroundRecorders.set(recorders.size)
        if (recorders.isEmpty()) ownRecorderSilenced = false
        refresh?.invoke()
    }
    fun recordingCount(): Int = withRecorderLock { recorders.values.count { it.recording() } }
    fun installMonitor(listener: (() -> Unit)?) = withRecorderLock {
        refresh = listener
        if (listener == null) { pendingRequest = false; interrupted.value = false }
    }
    fun consumeRequest(): Boolean = withRecorderLock {
        pendingRequest.also { pendingRequest = false }
    }
    fun requestInterruption(reason: String) = withRecorderLock {
        // Standalone wake tests have no monitor to clear a persistent latch.
        // Their local input still stops; dictationRequested itself holds dictation priority.
        if (refresh != null) {
            pendingRequest = true
            interrupted.value = true
        }
        // Stop hardware capture immediately. Its read job remains the sole release owner.
        // Do not wait for ASR/TTS teardown or a main-thread runtime callback.
        recorders.values.forEach { it.stop() }
        CommunicationAudioSession.releaseForInterruption()
        record("priority_request reason=$reason own=${recorders.size}")
        refresh?.invoke()
    }
    fun requestDictation(): Boolean {
        if (!requested.compareAndSet(false, true)) return false
        requestInterruption("jarvis_dictation")
        return true
    }
    fun finishDictation() {
        requested.set(false)
        withRecorderLock { refresh?.invoke() }
    }
    @Synchronized fun record(message: String) {
        events.addLast("atMs=${System.currentTimeMillis()} $message")
        while (events.size > 32) events.removeFirst()
    }
    @Synchronized fun clearDiagnostics() { events.clear() }
    @Synchronized fun diagnostics(): String =
        "Microphone handoff: interrupted=${interrupted.value} silenced=$ownRecorderSilenced dictation=$dictationRequested ownRecorders=${backgroundRecorders.get()} keyboardHelper=${keyboardHelperConnected.value}\n" + events.joinToString("\n")
}
