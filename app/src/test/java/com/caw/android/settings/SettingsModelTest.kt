package com.caw.android.settings

import com.caw.android.remote.DesktopSettings
import com.caw.android.remote.Shortcut
import com.caw.android.settings.SettingsModel.ComputerLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {

    private val loaded = DesktopSettings("omarchy", "0.1.0", mapOf("car app" to "Caw app"), 1.5, 0.05)

    @Test
    fun computerLine() {
        assertEquals(ComputerLine.NOT_PAIRED, SettingsModel.computerLine(paired = false, connected = false, loading = false, settings = null))
        assertEquals(ComputerLine.NOT_CONNECTED, SettingsModel.computerLine(paired = true, connected = false, loading = true, settings = loaded))
        assertEquals(ComputerLine.LOADING, SettingsModel.computerLine(paired = true, connected = true, loading = true, settings = null))
        assertEquals(ComputerLine.LOADED, SettingsModel.computerLine(paired = true, connected = true, loading = false, settings = loaded))
        assertEquals(ComputerLine.NOT_LOADED, SettingsModel.computerLine(paired = true, connected = true, loading = false, settings = null))
    }

    @Test
    fun editorsNeedTheComputerAndItsSettings() {
        assertTrue(SettingsModel.editable(paired = true, connected = true, settings = loaded))
        assertFalse(SettingsModel.editable(paired = true, connected = false, settings = loaded))
        assertFalse(SettingsModel.editable(paired = true, connected = true, settings = null))
        assertFalse(SettingsModel.editable(paired = false, connected = false, settings = null))
    }

    @Test
    fun addEditDelete() {
        val fixes = mapOf("car app" to "Caw app", "crumb" to "Crumb")
        // Add: the heard phrase is stored lowercase, trimmed; the rest of the list stays.
        val added = SettingsModel.withFix(fixes, null, "  Cause  Desktop ", " Caw Desktop ")
        assertEquals(mapOf("car app" to "Caw app", "crumb" to "Crumb", "cause desktop" to "Caw Desktop"), added)
        // Edit the heard phrase: the old entry goes.
        val edited = SettingsModel.withFix(fixes, "car app", "call app", "Caw app")
        assertEquals(mapOf("crumb" to "Crumb", "call app" to "Caw app"), edited)
        // Adding a phrase that is already there replaces its replacement.
        assertEquals(mapOf("car app" to "Car App", "crumb" to "Crumb"), SettingsModel.withFix(fixes, null, "CAR APP", "Car App"))
        assertEquals(mapOf("crumb" to "Crumb"), SettingsModel.withoutFix(fixes, "car app"))
        // The original is never changed.
        assertEquals(2, fixes.size)
    }

    @Test
    fun rowsAreSortedByWhatItHears() {
        assertEquals(listOf("alpha", "beta", "gamma"), SettingsModel.rows(mapOf("gamma" to "g", "alpha" to "a", "beta" to "b")).map { it.first })
    }

    @Test
    fun sliderSteps() {
        assertEquals(0, SettingsModel.speedProgress(0.1))
        assertEquals(14, SettingsModel.speedProgress(1.5))
        assertEquals(SettingsModel.SPEED_STEPS, SettingsModel.speedProgress(10.0))
        assertEquals(1.5, SettingsModel.speedAt(14), 0.0)
        assertEquals(10.0, SettingsModel.speedAt(SettingsModel.SPEED_STEPS), 0.0)
        assertEquals(0.1, SettingsModel.speedAt(0), 0.0)
        assertEquals(5, SettingsModel.accelProgress(0.05))
        assertEquals(0.05, SettingsModel.accelAt(5), 0.0)
        assertEquals(1.0, SettingsModel.accelAt(SettingsModel.ACCEL_STEPS), 0.0)
        // Every step reads back as itself.
        for (p in 0..SettingsModel.SPEED_STEPS) assertEquals(p, SettingsModel.speedProgress(SettingsModel.speedAt(p)))
        for (p in 0..SettingsModel.ACCEL_STEPS) assertEquals(p, SettingsModel.accelProgress(SettingsModel.accelAt(p)))
        assertEquals("1.5", SettingsModel.speedText(1.5))
        assertEquals("0.05", SettingsModel.accelText(0.05))
        assertEquals("10.0", SettingsModel.speedText(10.0))
    }

    @Test
    fun shortcutsAddEditDeleteMove() {
        val term = Shortcut("Terminal", "enter", listOf("super"))
        val tab = Shortcut("New tab", "t", listOf("ctrl", "shift"))
        // Added at the end; the name trimmed and the mods put in the desktop's order.
        val added = SettingsModel.withShortcut(listOf(term), null, Shortcut("  New tab ", "t", listOf("shift", "ctrl")))
        assertEquals(listOf(term, tab), added)
        // Edited in place.
        val edited = SettingsModel.withShortcut(added, 0, term.copy(name = "Shell"))
        assertEquals(listOf(term.copy(name = "Shell"), tab), edited)
        assertEquals(listOf(tab), SettingsModel.withoutShortcut(added, 0))
        assertEquals(listOf(tab, term), SettingsModel.moved(added, 0, +1))
        assertEquals(listOf(tab, term), SettingsModel.moved(added, 1, -1))
        // Unchanged at either end.
        assertEquals(added, SettingsModel.moved(added, 0, -1))
        assertEquals(added, SettingsModel.moved(added, 1, +1))
    }
}
