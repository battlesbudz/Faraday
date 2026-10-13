package com.battlesbudz.jarvis.v2.voice

/** A tab switch is display-only. Termination is reserved for the explicit End action. */
object VoiceNavigationPolicy {
    enum class Transition { SHOW_CHAT, SHOW_VOICE, EXPLICIT_END }
    fun endsCall(transition: Transition): Boolean = transition == Transition.EXPLICIT_END
    fun dispatch(transition: Transition, onEnd: () -> Unit) {
        if (endsCall(transition)) onEnd()
    }
}
