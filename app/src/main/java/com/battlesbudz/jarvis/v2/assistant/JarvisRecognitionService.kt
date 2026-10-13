package com.battlesbudz.jarvis.v2.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.battlesbudz.jarvis.v2.voice.*
import kotlinx.coroutines.*

/** The real local recognizer endpoint required by Android's assistant declaration. */
class JarvisRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var capture: AudioTurnCapture? = null
    override fun onStartListening(intent: Intent?, callback: Callback) {
        val manager = getSystemService(android.media.AudioManager::class.java)
        if (job?.isCompleted == false || !MicrophoneHandoff.requestDictation()) {
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        stopRequested = false
        job = scope.launch {
            var activeCapture: AudioTurnCapture? = null
            try {
                val engine = AsrEngine.selected(applicationContext)
                val directory = engine.prepare(applicationContext) {}
                activeCapture = AudioTurnCapture(AndroidAudioInput(this, audioManager = manager, dictation = true), this,
                    createDetector = { SileroSpeechDetector.create(assets) },
                    createTranscriber = { engine.create(directory) },
                    onPartialTranscript = { text ->
                        if (intent?.getBooleanExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) == true) {
                            handler.post { callback.partialResults(Bundle().apply {
                                putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                            }) }
                        }
                    })
                capture = activeCapture
                if (stopRequested) activeCapture.finishNow()
                withTimeout(10_000) { activeCapture.start() }
                handler.post { callback.readyForSpeech(Bundle()) }
                activeCapture.awaitTurnCompletion()
                val text = activeCapture.finalTranscript
                activeCapture.stop()
                handler.post {
                    callback.endOfSpeech()
                    if (text.isBlank()) callback.error(SpeechRecognizer.ERROR_NO_MATCH)
                    else callback.results(Bundle().apply {
                        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    })
                }
            } catch (timeout: TimeoutCancellationException) {
                handler.post { callback.error(SpeechRecognizer.ERROR_AUDIO) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (denied: SecurityException) { handler.post { callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) } }
            catch (error: Exception) { handler.post { callback.error(SpeechRecognizer.ERROR_AUDIO) } }
            finally {
                withContext(NonCancellable) { activeCapture?.stop() }
                if (capture === activeCapture) capture = null
            }
        }.also { recognitionJob ->
            // Completion also runs if cancellation occurs before the coroutine body starts.
            recognitionJob.invokeOnCompletion { MicrophoneHandoff.finishDictation() }
        }
    }
    override fun onStopListening(callback: Callback) { stopRequested = true; capture?.finishNow() }
    override fun onCancel(callback: Callback) { job?.cancel() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
