package com.battlesbudz.jarvis.v2.voice.speaker

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.battlesbudz.jarvis.v2.voice.RecognitionModelStore
import com.battlesbudz.jarvis.v2.voice.WavEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SpeakerEnrollmentStatus {
    data object NotEnrolled : SpeakerEnrollmentStatus
    data class Recording(val phraseIndex: Int) : SpeakerEnrollmentStatus
    data class Ready(val embeddings: Int) : SpeakerEnrollmentStatus
    data class Enrolled(val enrolledAtMs: Long, val phraseCount: Int) : SpeakerEnrollmentStatus
    data class Error(val message: String) : SpeakerEnrollmentStatus
}
data class SpeakerEnrollmentState(val status: SpeakerEnrollmentStatus = SpeakerEnrollmentStatus.NotEnrolled,
    val lastMessage: String? = null)

internal fun interface EnrollmentRecorder { fun record(context: Context): FloatArray }

internal class AndroidEnrollmentRecorder : EnrollmentRecorder {
    override fun record(context: Context): FloatArray {
        check(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required to enroll."
        }
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16_000))
        try {
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            recorder.startRecording(); val until = System.nanoTime() + 3_000_000_000L
            while (System.nanoTime() < until) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) bytes.write(buffer, 0, n)
            }
            val pcm = bytes.toByteArray()
            return FloatArray(pcm.size / 2) { i -> (((pcm[2*i].toInt() and 255) or (pcm[2*i+1].toInt() shl 8)).toShort()).toFloat() / 32768f }
        } finally { recorder.stop(); recorder.release() }
    }
}

class SpeakerEnrollmentController internal constructor(
    context: Context, private val recorder: EnrollmentRecorder = AndroidEnrollmentRecorder(),
    private val embedderFactory: (File) -> SpeakerEmbedder = ::SherpaSpeakerEmbedder
) {
    private val app = context.applicationContext
    private val store = SpeakerProfileStore(File(app.filesDir, "speaker-gate"))
    private val mutable = MutableStateFlow(SpeakerEnrollmentState())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val embeddings = mutableListOf<FloatArray>()
    init { refresh() }
    fun refresh() { val p = store.load(); mutable.value = SpeakerEnrollmentState(p?.let { SpeakerEnrollmentStatus.Enrolled(it.enrolledAtMs, it.phraseCount) } ?: SpeakerEnrollmentStatus.NotEnrolled) }
    internal fun setStateForTests(state: SpeakerEnrollmentState) { mutable.value = state }
    fun startEnrollment() { embeddings.clear(); mutable.value = SpeakerEnrollmentState(SpeakerEnrollmentStatus.Ready(0)) }
    fun recordPhrase(index: Int) {
        require(index in 0..2)
        scope.launch {
            mutable.value = SpeakerEnrollmentState(SpeakerEnrollmentStatus.Recording(index))
            try {
                val model = RecognitionModelStore(app).speakerEmbedding { msg -> mutable.value = mutable.value.copy(lastMessage = msg) }
                SpeakerGate.refresh(app)
                val wav = WavEncoder.pcm16Mono(recorder.record(app).let { samples ->
                    ByteArray(samples.size * 2).also { b -> samples.forEachIndexed { i, f ->
                        val s = (f.coerceIn(-1f, 1f) * 32767).toInt(); b[2*i] = s.toByte(); b[2*i+1] = (s shr 8).toByte()
                    } }
                })
                val decoded = requireNotNull(WavPcm.decode(wav))
                val embedder = embedderFactory(File(model, "model.onnx"))
                val embedding = try { requireNotNull(embedder.embed(decoded.first, decoded.second)) } finally { embedder.close() }
                embeddings += embedding
                mutable.value = SpeakerEnrollmentState(SpeakerEnrollmentStatus.Ready(embeddings.size), "Phrase ${index + 1} recorded.")
            } catch (e: Throwable) { mutable.value = SpeakerEnrollmentState(SpeakerEnrollmentStatus.Error(e.message ?: "Enrollment failed")) }
        }
    }
    fun finishEnrollment() { if (embeddings.size < 3) { mutable.value = SpeakerEnrollmentState(SpeakerEnrollmentStatus.Error("Record all three phrases first.")); return }
        runCatching { val now = System.currentTimeMillis(); store.save(embeddings.toList(), now); refresh() }
            .onFailure { mutable.value = SpeakerEnrollmentState(SpeakerEnrollmentStatus.Error(it.message ?: "Could not save profile")) }
    }
    fun deleteProfile() { store.delete(); embeddings.clear(); refresh() }
    fun reEnroll() { deleteProfile(); startEnrollment() }
    companion object {
        @Volatile private var instance: SpeakerEnrollmentController? = null
        fun get(context: Context): SpeakerEnrollmentController = instance ?: synchronized(this) {
            instance ?: SpeakerEnrollmentController(context.applicationContext).also { instance = it }
        }
        internal fun setForTests(controller: SpeakerEnrollmentController?) { instance = controller }
    }
}
