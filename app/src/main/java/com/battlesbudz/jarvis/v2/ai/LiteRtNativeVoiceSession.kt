package com.battlesbudz.jarvis.v2.ai

import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseCallback
import com.google.ai.edge.litertlm.Session

/** SDK boundary. The streaming lifecycle can be tested without loading Android JNI classes. */
internal class LiteRtNativeVoiceSession(
    private val session: Session,
    private val checkWorkerThread: () -> Unit = {},
    private val runCallback: (() -> Unit) -> Unit = { it() }
) : VoiceNativeSession {
    override fun runPrefill(input: List<String>) {
        checkWorkerThread()
        session.runPrefill(input.map { InputData.Text(it) })
    }

    override fun generateContentStream(input: List<String>, callback: VoiceNativeCallback) {
        checkWorkerThread()
        require(input.isNotEmpty()) { "Streaming generation requires a final input chunk" }
        session.generateContentStream(input.map { InputData.Text(it) }, object : ResponseCallback {
            override fun onNext(response: String) = runCallback { callback.onNext(response) }
            override fun onDone() = runCallback { callback.onDone() }
            override fun onError(throwable: Throwable) = runCallback { callback.onError(throwable) }
        })
    }

    override fun cancelProcess() { checkWorkerThread(); session.cancelProcess() }
    override fun awaitIdle() { checkWorkerThread(); session.awaitIdle() }
    override fun close() { checkWorkerThread(); session.close() }
}
