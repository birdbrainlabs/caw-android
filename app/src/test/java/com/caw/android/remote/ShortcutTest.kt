package com.caw.android.remote

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutTest {

    @Test
    fun combosReadAsOnAKeyboard() {
        assertEquals("Super+Enter", ShortcutKeys.combo("enter", listOf("super")))
        assertEquals("Ctrl+Shift+T", ShortcutKeys.combo("t", listOf("ctrl", "shift")))
        // Always Super, Ctrl, Alt, Shift, whatever order they were stored in.
        assertEquals("Super+Ctrl+Alt+Shift+F12", ShortcutKeys.combo("f12", listOf("shift", "alt", "ctrl", "super")))
        assertEquals("F5", ShortcutKeys.combo("f5", emptyList()))
        assertEquals("Page Up", ShortcutKeys.combo("page_up", emptyList()))
        assertEquals("Ctrl+-", ShortcutKeys.combo("minus", listOf("ctrl")))
        assertEquals("Alt+`", ShortcutKeys.combo(Shortcut("Windows", "grave", listOf("alt"))))
        assertEquals("7", ShortcutKeys.label("7"))
        assertEquals("\\", ShortcutKeys.label("backslash"))
    }

    @Test
    fun pickerOffersEveryKeyOnce() {
        assertEquals(Protocol.KEYS.toSet(), ShortcutKeys.PICKER.toSet())
        assertEquals(ShortcutKeys.PICKER.size, ShortcutKeys.PICKER.distinct().size)
        assertEquals("enter", ShortcutKeys.PICKER.first())
    }

    @Test
    fun validity() {
        assertTrue(Shortcut("Terminal", "enter", listOf("super")).valid)
        assertFalse(Shortcut("x", "f13").valid)
        assertFalse(Shortcut("x", "a", listOf("hyper")).valid)
        assertFalse(Shortcut("x", "a", listOf("ctrl", "ctrl")).valid)
    }

    @Test
    fun jsonRoundTripSkipsWhatThePhoneCannotSend() {
        val list = listOf(Shortcut("Terminal", "enter", listOf("super")), Shortcut("Refresh", "f5"))
        assertEquals(list, Shortcut.listFromJson(Shortcut.listToJson(list)))
        val a = JSONArray("""[{"name":"A","key":"a","mods":["ctrl"]},{"name":"B","key":"nope"},"junk",{"name":"C","key":"c","mods":["meta"]}]""")
        assertEquals(listOf(Shortcut("A", "a", listOf("ctrl"))), Shortcut.listFromJson(a))
    }

    @Test
    fun cacheKeepsTheListOrUnsupported() {
        val list = listOf(Shortcut("Terminal", "enter", listOf("super")), Shortcut("New tab", "t", listOf("ctrl", "shift")))
        assertEquals(list, ShortcutCache.decode(ShortcutCache.encode(list)))
        // Empty (supported, none yet) is not the same as unsupported.
        assertEquals(emptyList<Shortcut>(), ShortcutCache.decode(ShortcutCache.encode(emptyList())))
        assertEquals(ShortcutCache.UNSUPPORTED, ShortcutCache.encode(null))
        assertNull(ShortcutCache.decode(ShortcutCache.UNSUPPORTED))
        // Nothing stored, or something unreadable: no buttons.
        assertNull(ShortcutCache.decode(null))
        assertNull(ShortcutCache.decode("{not json"))
    }
}
