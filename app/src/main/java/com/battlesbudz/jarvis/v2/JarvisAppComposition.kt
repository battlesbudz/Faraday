package com.battlesbudz.jarvis.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.battlesbudz.jarvis.v2.diagnostics.AndroidPipelineBenchmarkStore
import com.battlesbudz.jarvis.v2.ui.CallEvidenceActions
import com.battlesbudz.jarvis.v2.ui.CallEvidenceSnapshot
import com.battlesbudz.jarvis.v2.ui.CodeDestinationAccess
import com.battlesbudz.jarvis.v2.voice.LiveCallAudioEvidence
import com.battlesbudz.jarvis.v2.voice.MicrophoneHandoff

/** Compatibility composition boundary for the established JarvisApp entrypoint/test ABI. */
@Composable
internal fun WithPipelineDiagnostics(
    content: @Composable (AndroidPipelineBenchmarkStore, CallEvidenceActions, CodeDestinationAccess) -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val runtime = remember(context) { JarvisRuntime.get(context) }
    val callEvidence = remember(runtime) {
        CallEvidenceActions(
            armAudio = LiveCallAudioEvidence::arm,
            clearAudio = LiveCallAudioEvidence::clear,
            snapshot = {
                val audio = LiveCallAudioEvidence.snapshot()
                CallEvidenceSnapshot(
                    report = "Jarvis live call diagnostics\n" +
                        "scope=bounded_retained_call_diagnostics microphoneRecording=${audio != null} acousticCancellation=not_measured\n" +
                        "Audio is retained only when opted in, with fixed memory limits; long calls may be truncated.\n\n" +
                        runtime.diagnosticRecorder.snapshot() + "\n\n" + MicrophoneHandoff.diagnostics(),
                    audio = audio,
                )
            },
        )
    }
    // Code-and-export Unit 6: the Code destination's narrow dependencies,
    // resolved lazily by the holder so fixtures never build them.
    val codeAccess = remember(runtime) {
        CodeDestinationAccess(
            storeProvider = { runtime.codeProjectStore },
            statsStoreProvider = { runtime.codeStatsStore },
            engineAccessProvider = { runtime.codeEngineAccess },
        )
    }
    content(runtime.pipelineBenchmarkStore, callEvidence, codeAccess)
}
