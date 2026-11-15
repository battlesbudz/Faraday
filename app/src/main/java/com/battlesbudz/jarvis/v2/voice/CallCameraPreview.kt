package com.battlesbudz.jarvis.v2.voice

import androidx.camera.core.Preview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Video-call step 5: the live CameraX [Preview] use case of the current
 * capture, published for the call screen.
 *
 * The binder's platform binds Preview alongside ImageAnalysis in the same
 * CameraX lifecycle (same back camera, same frames the vision pipeline
 * analyzes — never a second camera session) and publishes the use case
 * here. The call screen collects [preview] and points its PreviewView's
 * surface provider at it; when the capture's detach is confirmed, the
 * platform clears it and the preview area goes away. Null whenever no
 * capture is bound: video off, cleanup pending, or camera failed.
 */
object CallCameraPreview {
    private val previewFlow = MutableStateFlow<Preview?>(null)

    /** The bound capture's preview use case, or null when none is bound. */
    val preview: StateFlow<Preview?> = previewFlow

    /** The platform bound [preview]: make it available to the call screen. */
    internal fun publish(preview: Preview) {
        previewFlow.value = preview
    }

    /**
     * The platform's detach for [preview] was confirmed: stop offering it.
     * Identity-checked so a stale detach can never clear a newer capture's
     * preview.
     */
    internal fun clear(preview: Preview) {
        if (previewFlow.value === preview) previewFlow.value = null
    }
}
