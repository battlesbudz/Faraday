package com.battlesbudz.jarvis.v2.actions

import android.os.Bundle
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OperatedTextEntryTest {
    @Test fun virtualKeyboardMapsLettersShiftSpaceDigitsAndPeriod() {
        val mapped = "testing".map { AndroidKeyEventMapper.eventsFor(it).single() }
        assertEquals(listOf(KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_G), mapped.map { it.keyCode })
        assertTrue(mapped.all { it.metaState == 0 })
        assertTrue(AndroidKeyEventMapper.eventsFor('T').any { it.metaState and KeyEvent.META_SHIFT_ON != 0 })
        assertNotNull(AndroidKeyEventMapper.eventsFor(' ').firstOrNull { it.keyCode == KeyEvent.KEYCODE_SPACE })
        assertNotNull(AndroidKeyEventMapper.eventsFor('7').firstOrNull { it.keyCode == KeyEvent.KEYCODE_7 })
        assertNotNull(AndroidKeyEventMapper.eventsFor('.').firstOrNull { it.keyCode == KeyEvent.KEYCODE_PERIOD })
    }

    @Test fun sendsOneMetaStateBundleForEachMappedPressAndReportsUnknownCharacters() {
        val bundles = mutableListOf<Bundle>()
        val entry = OperatedTextEntry(OperatedInputInjector { id, event ->
            assertEquals(7, id)
            bundles += Bundle(event)
            true
        }, KeyEventMapper { ch -> if (ch == '?') emptyList() else listOf(KeyPress(KeyEvent.KEYCODE_A, if (ch.isUpperCase()) KeyEvent.META_SHIFT_ON else 0)) })
        val result = entry.type(7, "aA?")
        assertFalse(result.succeeded)
        assertEquals(1, result.unmappableCharacters)
        assertEquals(2, bundles.size)
        bundles.forEach {
            assertEquals("key", it.getString("type"))
            assertEquals(KeyEvent.KEYCODE_A, it.getInt("keyCode"))
            assertTrue(it.containsKey("metaState"))
        }
        assertEquals(0, bundles[0].getInt("metaState"))
        assertEquals(KeyEvent.META_SHIFT_ON, bundles[1].getInt("metaState"))
    }
}
