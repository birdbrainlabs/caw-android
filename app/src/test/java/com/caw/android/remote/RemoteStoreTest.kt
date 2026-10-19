package com.caw.android.remote

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteStoreTest {

    private val desk = PairedDesktop("0123456789abcdef0123456789abcdef", "Desk", "ab".repeat(32), "192.168.1.20", 47820)
    private val lab = PairedDesktop("fedcba9876543210fedcba9876543210", "Lab", "cd".repeat(32), "192.168.1.30", 47821)

    /** The prefs a 0.3 phone paired with Desk has, with Desk's shortcut cache. */
    private fun paired03() = FakePrefs().apply {
        map["desktop_id"] = desk.id
        map["desktop_name"] = desk.name
        map["desktop_fingerprint"] = desk.fingerprintHex
        map["desktop_host"] = desk.host
        map["desktop_port"] = desk.port
        map["send_to_computer"] = true
        map["natural_scrolling"] = false
        map["shortcuts_desktop_id"] = desk.id
        map["shortcuts"] = """[{"name":"Run","key":"enter","mods":["super"]}]"""
    }

    @Test
    fun aPhonePairedIn03KeepsItsComputerAsTheActiveOne() {
        val prefs = paired03()
        val store = RemoteStore(prefs)
        assertEquals(listOf(desk), store.desktops())
        assertEquals(desk, store.desktop())
        assertTrue(store.enabled)
        assertFalse(store.naturalScrolling)
        assertEquals(listOf(Shortcut("Run", "enter", listOf("super"))), store.shortcuts(desk.id))
        // The old keys are gone; a second store (the next start) reads the same.
        for (key in listOf("desktop_id", "desktop_name", "desktop_fingerprint", "desktop_host", "desktop_port", "shortcuts_desktop_id", "shortcuts")) {
            assertFalse(key, prefs.contains(key))
        }
        assertEquals(desk, RemoteStore(prefs).desktop())
    }

    @Test
    fun migrationWithoutAStoredPortUsesTheDefault() {
        val prefs = paired03().apply { map.remove("desktop_port") }
        assertEquals(PairTarget.DEFAULT_PORT, RemoteStore(prefs).desktop()?.port)
    }

    @Test
    fun anOldCacheForAnotherComputerIsNotCarriedOver() {
        val prefs = paired03().apply { map["shortcuts_desktop_id"] = lab.id }
        assertNull(RemoteStore(prefs).shortcuts(desk.id))
    }

    @Test
    fun aPhoneNeverPairedHasNoComputers() {
        val store = RemoteStore(FakePrefs())
        assertEquals(emptyList<PairedDesktop>(), store.desktops())
        assertNull(store.desktop())
    }

    @Test
    fun migrationRunsOnce() {
        val prefs = paired03()
        val store = RemoteStore(prefs)
        store.remove(desk.id)
        // Nothing of 0.3 is left to bring the removed computer back.
        assertEquals(emptyList<PairedDesktop>(), RemoteStore(prefs).desktops())
    }

    @Test
    fun pairingAddsAndPairingAgainUpdatesInPlace() {
        val store = RemoteStore(paired03())
        store.saveDesktop(lab)
        assertEquals(listOf(desk, lab), store.desktops())
        assertEquals(desk, store.desktop())
        val moved = desk.copy(name = "Desk 2", host = "192.168.1.21", port = 47900)
        store.saveDesktop(moved)
        assertEquals(listOf(moved, lab), store.desktops())
    }

    @Test
    fun theActiveChoiceIsRemembered() {
        val prefs = paired03()
        RemoteStore(prefs).apply {
            saveDesktop(lab)
            setActive(lab.id)
        }
        assertEquals(lab, RemoteStore(prefs).desktop())
    }

    @Test
    fun shortcutCachesArePerComputer() {
        val store = RemoteStore(paired03())
        store.saveDesktop(lab)
        store.saveShortcuts(lab.id, emptyList())
        assertEquals(1, store.shortcuts(desk.id)?.size)
        assertEquals(emptyList<Shortcut>(), store.shortcuts(lab.id))
    }

    @Test
    fun removingTheActiveComputerLeavesNoneActiveAndTheSwitchAlone() {
        val store = RemoteStore(paired03())
        store.saveDesktop(lab)
        store.remove(desk.id)
        assertEquals(listOf(lab), store.desktops())
        assertNull(store.desktop())
        assertNull(store.shortcuts(desk.id))
        assertTrue(store.enabled)
    }

    @Test
    fun removingAnotherComputerKeepsTheActiveOne() {
        val store = RemoteStore(paired03())
        store.saveDesktop(lab)
        store.saveShortcuts(lab.id, emptyList())
        store.remove(lab.id)
        assertEquals(desk, store.desktop())
        assertNull(store.shortcuts(lab.id))
    }

    @Test
    fun removingTheLastComputerIsLikeNeverPairing() {
        val store = RemoteStore(paired03())
        store.remove(desk.id)
        assertEquals(emptyList<PairedDesktop>(), store.desktops())
        assertNull(store.desktop())
        assertFalse(store.enabled)
    }

    /** SharedPreferences in a map (android.jar's real one is not available in unit tests). */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(map)
        override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = map[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
        override fun contains(key: String) = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        inner class Editor : SharedPreferences.Editor {
            private val puts = HashMap<String, Any?>()
            private val removes = HashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { puts[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { puts[key] = values }
            override fun putInt(key: String, value: Int) = apply { puts[key] = value }
            override fun putLong(key: String, value: Long) = apply { puts[key] = value }
            override fun putFloat(key: String, value: Float) = apply { puts[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { puts[key] = value }
            override fun remove(key: String) = apply { removes += key }
            override fun clear() = apply { clear = true }

            override fun commit(): Boolean {
                // As Android does: clear, then removals, then puts.
                if (clear) map.clear()
                removes.forEach { map.remove(it) }
                puts.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }
}
