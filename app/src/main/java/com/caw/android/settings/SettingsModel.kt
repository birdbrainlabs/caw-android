package com.caw.android.settings

import com.caw.android.remote.DesktopSettings
import com.caw.android.remote.Protocol
import com.caw.android.remote.Shortcut
import com.caw.android.remote.SettingsLimits
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Settings screen's logic without Android: which computer line to
 * show, when the editors are usable, word-fix and shortcut list edits, and the sliders' steps. The texts are in
 * res/values/strings.xml; [SettingsActivity][com.caw.android.SettingsActivity] maps [ComputerLine] to them.
 */
object SettingsModel {

    enum class ComputerLine {
        /** "No computer paired. Set one up on the Computer screen." */
        NOT_PAIRED,
        /** "Not connected to Desk" (editors disabled). */
        NOT_CONNECTED,
        /** "Loading settings…" */
        LOADING,
        /** "Desk · Caw Desktop 0.1.0" */
        LOADED,
        /** Connected, but the settings did not come (the status line says why). */
        NOT_LOADED,
    }

    fun computerLine(paired: Boolean, connected: Boolean, loading: Boolean, settings: DesktopSettings?): ComputerLine = when {
        !paired -> ComputerLine.NOT_PAIRED
        !connected -> ComputerLine.NOT_CONNECTED
        loading -> ComputerLine.LOADING
        settings != null -> ComputerLine.LOADED
        else -> ComputerLine.NOT_LOADED
    }

    /** The word-fix and pointer editors work only with the computer connected and its settings loaded. */
    fun editable(paired: Boolean, connected: Boolean, settings: DesktopSettings?): Boolean =
        paired && connected && settings != null

    // ---- word fixes ----

    /** The list as shown: sorted by what it hears. */
    fun rows(fixes: Map<String, String>): List<Pair<String, String>> =
        fixes.entries.sortedBy { it.key.lowercase(Locale.ROOT) }.map { it.key to it.value }

    /**
     * [fixes] with one fix added or edited: [previous] is the heard phrase of the row being edited
     * (null when adding). The heard phrase is stored as the desktop does (lowercase); a phrase that
     * is already in the list gets the new replacement.
     */
    fun withFix(fixes: Map<String, String>, previous: String?, heard: String, write: String): Map<String, String> {
        val out = LinkedHashMap(fixes)
        if (previous != null) out.remove(previous)
        out[SettingsLimits.phrase(heard)] = write.trim()
        return out
    }

    fun withoutFix(fixes: Map<String, String>, heard: String): Map<String, String> =
        LinkedHashMap(fixes).apply { remove(heard) }

    // ---- shortcuts (v4): shown in the computer's order; rows are found by position, as names may repeat ----

    /** [shortcuts] with the one at [index] replaced by [shortcut], or [shortcut] added at the end (null [index]). */
    fun withShortcut(shortcuts: List<Shortcut>, index: Int?, shortcut: Shortcut): List<Shortcut> {
        val s = shortcut.copy(name = shortcut.name.trim(), mods = Protocol.MODS.filter { it in shortcut.mods })
        return if (index == null || index !in shortcuts.indices) shortcuts + s
        else shortcuts.toMutableList().apply { set(index, s) }
    }

    fun withoutShortcut(shortcuts: List<Shortcut>, index: Int): List<Shortcut> =
        shortcuts.filterIndexed { i, _ -> i != index }

    /** [shortcuts] with the one at [index] moved [by] places (-1 up, +1 down); unchanged at either end. */
    fun moved(shortcuts: List<Shortcut>, index: Int, by: Int): List<Shortcut> {
        val to = index + by
        if (index !in shortcuts.indices || to !in shortcuts.indices) return shortcuts
        return shortcuts.toMutableList().apply { add(to, removeAt(index)) }
    }

    // ---- sliders: speed 0.1-10 in steps of 0.1, acceleration 0-1 in steps of 0.01 ----

    const val SPEED_STEPS = 99
    const val ACCEL_STEPS = 100

    fun speedProgress(speed: Double): Int =
        ((speed.coerceIn(SettingsLimits.SPEED_MIN, SettingsLimits.SPEED_MAX) - SettingsLimits.SPEED_MIN) * 10).roundToInt()

    fun speedAt(progress: Int): Double = round(SettingsLimits.SPEED_MIN + progress.coerceIn(0, SPEED_STEPS) / 10.0, 10.0)

    fun accelProgress(accel: Double): Int =
        (accel.coerceIn(SettingsLimits.ACCEL_MIN, SettingsLimits.ACCEL_MAX) * 100).roundToInt()

    fun accelAt(progress: Int): Double = round(progress.coerceIn(0, ACCEL_STEPS) / 100.0, 100.0)

    fun speedText(speed: Double): String = String.format(Locale.US, "%.1f", speed)

    fun accelText(accel: Double): String = String.format(Locale.US, "%.2f", accel)

    private fun round(v: Double, scale: Double): Double = Math.round(v * scale) / scale
}
