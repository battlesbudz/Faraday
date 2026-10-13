package com.battlesbudz.jarvis.v2.voice

sealed interface VoiceActionControl {
 data object SpeechOnly : VoiceActionControl
 data object CancelNewest : VoiceActionControl
 data object CancelCurrent : VoiceActionControl
 data object CancelAll : VoiceActionControl
 data object CancelQueued : VoiceActionControl
 data object None : VoiceActionControl
 companion object {
  fun parse(text:String, hasUnfinished:Boolean): VoiceActionControl {
   if (text.any { it in "\"“”" }) return None
   val t=text.trim().lowercase().trimEnd('.','!','?')
   if (Regex("""\b(?:don't|do not|never)\b""").containsMatchIn(t)) return None
   if (t in setOf("cancel all actions", "stop all actions", "cancel all queued actions", "stop all queued actions",
       "cancel all tasks", "stop all tasks")) return if(t.contains("queued")) CancelQueued else CancelAll
   if (t in setOf("cancel the current action", "cancel the current request", "stop the current action", "stop the current request",
       "cancel your task", "stop your task", "cancel this task", "stop this task", "cancel my task", "stop my task")) return CancelCurrent
   if (t in setOf("cancel that action", "cancel that request", "stop that action", "stop that request") ||
       (hasUnfinished && t in setOf("cancel", "cancel that", "never mind", "nevermind"))) return CancelNewest
   if (t in setOf("stop", "stop speaking", "stop talking", "wait", "hold on", "pause microphone", "stop listening") ||
       (!hasUnfinished && t in setOf("cancel", "cancel that", "never mind", "nevermind"))) return SpeechOnly
   return None
  }
 }
}
