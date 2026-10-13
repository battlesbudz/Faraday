package com.battlesbudz.jarvis.v2.presentation

/** Durable WorkManager identity shared by setup commands and their UI observer. */
internal const val MODEL_SETUP_WORK_NAME = "jarvis-local-model-setup"

/** Only session capabilities model management needs; implemented at the Activity boundary. */
internal interface ModelSetupSession {
    fun busy(): Boolean
    fun closeConversation(resetCharacters: Boolean)
    fun record(message: String)
}
