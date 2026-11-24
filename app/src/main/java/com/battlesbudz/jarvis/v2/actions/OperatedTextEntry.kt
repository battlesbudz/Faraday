package com.battlesbudz.jarvis.v2.actions

import android.os.Bundle
import android.view.KeyCharacterMap
import android.view.KeyEvent

fun interface KeyEventMapper { fun eventsFor(ch: Char): List<KeyPress> }
data class KeyPress(val keyCode: Int, val metaState: Int)
data class TypeResult(val succeeded: Boolean, val unmappableCharacters: Int, val rejectedPresses: Int)

class OperatedTextEntry(
    private val injector: OperatedInputInjector,
    private val mapper: KeyEventMapper,
) {
    fun type(displayId: Int, text: String): TypeResult {
        require(displayId > 0)
        var unmappable = 0
        var rejected = 0
        text.forEach { char ->
            val presses = mapper.eventsFor(char).filter { it.keyCode != KeyEvent.KEYCODE_UNKNOWN }
            if (presses.isEmpty()) unmappable++
            presses.forEach { press ->
                val accepted = runCatching {
                    injector.inject(displayId, Bundle().apply {
                        putString("type", "key")
                        putInt("keyCode", press.keyCode)
                        putInt("metaState", press.metaState)
                    })
                }.getOrDefault(false)
                if (!accepted) rejected++
            }
        }
        return TypeResult(unmappable == 0 && rejected == 0, unmappable, rejected)
    }
}

object AndroidKeyEventMapper : KeyEventMapper {
    override fun eventsFor(ch: Char): List<KeyPress> {
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(charArrayOf(ch)) ?: return emptyList()
        return events.filter { it.action == KeyEvent.ACTION_DOWN }
            .map { KeyPress(it.keyCode, it.metaState) }
    }
}
