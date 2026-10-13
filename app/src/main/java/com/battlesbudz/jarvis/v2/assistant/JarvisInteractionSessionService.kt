package com.battlesbudz.jarvis.v2.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.battlesbudz.jarvis.v2.MainActivity

class JarvisInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onPrepareShow(args: Bundle?, showFlags: Int) { super.onPrepareShow(args, showFlags); setUiEnabled(false) }
        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            startAssistantActivity(Intent(this@JarvisInteractionSessionService, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
            hide()
        }
    }
}
