package com.battlesbudz.jarvis.v2.voice

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/** Observes microphone-button clicks only. Never reads window roots, text, or keyboard visibility. */
class KeyboardMicrophoneService : AccessibilityService() {
    override fun onServiceConnected() { MicrophoneHandoff.keyboardHelperConnected.value = true }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED || !VoiceSessionUi.armed.value ||
            VoiceSessionUi.paused.value) return
        val keyboard = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let(ComponentName::unflattenFromString)?.packageName ?: return
        if (event.packageName?.toString() != keyboard) return
        val source = event.source
        try {
            if (KeyboardMicrophoneRequest.matches(keyboard, event.packageName?.toString(),
                    source?.contentDescription?.toString() ?: event.contentDescription?.toString(),
                    source?.viewIdResourceName, source?.isEditable == true)) {
                MicrophoneHandoff.requestInterruption("keyboard_microphone_button")
            }
        } finally {
            @Suppress("DEPRECATION")
            source?.recycle()
        }
    }
    override fun onInterrupt() { /* No reservation is tied to this service's lifetime. */ }
    override fun onDestroy() {
        MicrophoneHandoff.keyboardHelperConnected.value = false
        super.onDestroy()
    }
}
