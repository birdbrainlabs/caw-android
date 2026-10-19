package com.caw.android

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.caw.android.remote.DesktopSettings
import com.caw.android.remote.RemoteLink
import com.caw.android.remote.RemoteLink.SettingsReply
import com.caw.android.remote.RemoteStore
import com.caw.android.remote.SettingsChange
import com.caw.android.remote.SettingsLimits
import com.caw.android.remote.Shortcut
import com.caw.android.remote.ShortcutKeys
import com.caw.android.settings.SettingsModel
import com.caw.android.settings.SettingsModel.ComputerLine

/**
 * Settings, opened from the dictation screen. The word fixes, the shortcuts (v4) and the
 * pointer's speed and acceleration live on the paired computer (protocol v3 `settings_get` /
 * `settings_set`), so they follow whichever computer is paired; natural scrolling is kept on the
 * phone. While this is open Caw stays connected to the computer; nothing here is queued: a change
 * made while it is not connected is dropped with a status.
 */
class SettingsActivity : Activity(), RemoteLink.Observer {

    private val main = Handler(Looper.getMainLooper())

    private lateinit var computerLine: TextView
    private lateinit var fixList: LinearLayout
    private lateinit var fixesEmpty: TextView
    private lateinit var addFix: TextView
    private lateinit var fixesStatus: TextView
    private lateinit var shortcutList: LinearLayout
    private lateinit var shortcutsEmpty: TextView
    private lateinit var addShortcut: TextView
    private lateinit var shortcutsStatus: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var speedValue: TextView
    private lateinit var accelBar: SeekBar
    private lateinit var accelValue: TextView
    private lateinit var pointerStatus: TextView
    private lateinit var naturalSwitch: Switch

    private var link = RemoteLink.state
    private var wasConnected = false
    private var settings: DesktopSettings? = null
    private var loading = false
    /** A word-fix change is on its way: the list waits for its answer. */
    private var savingFixes = false
    /** The word fixes the rows show (rebuilt only when this changes). */
    private var shownFixes: Map<String, String>? = null
    private var shownEnabled: Boolean? = null
    /** A shortcut change is on its way: the list waits for its answer. */
    private var savingShortcuts = false
    /** The shortcut rows shown: the list (null = none on this computer) and whether they were enabled. */
    private var shownShortcuts: Pair<List<Shortcut>?, Boolean>? = null
    private var dialog: AlertDialog? = null
    /** The word-fix status line says the settings did not load (cleared when they do). */
    private var loadProblemShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        RemoteLink.init(this)

        computerLine = findViewById(R.id.computer_line)
        fixList = findViewById(R.id.fix_list)
        fixesEmpty = findViewById(R.id.fixes_empty)
        addFix = findViewById(R.id.add_fix)
        fixesStatus = findViewById(R.id.fixes_status)
        shortcutList = findViewById(R.id.shortcut_list)
        shortcutsEmpty = findViewById(R.id.shortcuts_empty)
        addShortcut = findViewById(R.id.add_shortcut)
        shortcutsStatus = findViewById(R.id.shortcuts_status)
        speedBar = findViewById(R.id.speed)
        speedValue = findViewById(R.id.speed_value)
        accelBar = findViewById(R.id.accel)
        accelValue = findViewById(R.id.accel_value)
        pointerStatus = findViewById(R.id.pointer_status)
        naturalSwitch = findViewById(R.id.natural_scrolling)

        addFix.setOnClickListener { editFix(null) }
        addShortcut.setOnClickListener { editShortcut(null) }
        speedBar.setOnSeekBarChangeListener(Slider(
            show = { speedValue.text = SettingsModel.speedText(SettingsModel.speedAt(it)) },
            send = { sendPointer(SettingsChange(pointerSpeed = SettingsModel.speedAt(it))) },
        ))
        accelBar.setOnSeekBarChangeListener(Slider(
            show = { accelValue.text = SettingsModel.accelText(SettingsModel.accelAt(it)) },
            send = { sendPointer(SettingsChange(pointerAccel = SettingsModel.accelAt(it))) },
        ))
        val store = RemoteStore(this)
        naturalSwitch.isChecked = store.naturalScrolling
        naturalSwitch.setOnCheckedChangeListener { _, on -> store.naturalScrolling = on }
        showPointer(null)
    }

    override fun onStart() {
        super.onStart()
        wasConnected = false
        RemoteLink.addObserver(this)
        RemoteLink.holdOpen(RemoteLink.SCREEN_SETTINGS, true)
        RemoteLink.kick()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStop() {
        dialog?.dismiss()
        dialog = null
        RemoteLink.removeObserver(this)
        RemoteLink.holdOpen(RemoteLink.SCREEN_SETTINGS, false)
        super.onStop()
    }

    // ---- the link ----

    override fun onState(state: RemoteLink.State) {
        // Another computer was chosen (or none): what was loaded was the other computer's.
        val switched = state.desktop?.id != link.desktop?.id
        link = state
        if (state.desktop == null || switched) {
            settings = null
            loading = false
        }
        if (switched) showPointer(null)
        // Each (re)connection reads the settings again: they may have changed on the computer.
        if (state.connected && !wasConnected) load()
        if (!state.connected) loading = false
        wasConnected = state.connected
        render()
    }

    private fun load() {
        loading = true
        RemoteLink.getSettings { reply ->
            if (isDestroyed) return@getSettings
            loading = false
            when (reply) {
                is SettingsReply.Loaded -> {
                    settings = reply.settings
                    showPointer(reply.settings)
                    if (loadProblemShown) status(fixesStatus, null)
                    loadProblemShown = false
                }
                is SettingsReply.Refused -> {
                    status(fixesStatus, getString(R.string.settings_load_refused, reply.reason))
                    loadProblemShown = true
                }
                SettingsReply.NoAnswer -> {
                    status(fixesStatus, getString(R.string.settings_load_no_answer, computerName()))
                    loadProblemShown = true
                }
                // The link went down in between; onState shows it and loads again when it is back.
                SettingsReply.NotConnected, SettingsReply.Saved -> Unit
            }
            render()
        }
        render()
    }

    private fun computerName(): String = settings?.desktopName?.takeIf { it.isNotBlank() } ?: link.desktop?.name ?: "The computer"

    // ---- rendering ----

    private fun render() {
        val d = link.desktop
        val s = settings
        computerLine.text = when (SettingsModel.computerLine(d != null, link.connected, loading, s)) {
            ComputerLine.NOT_PAIRED -> getString(if (link.desktops.isEmpty()) R.string.settings_not_paired else R.string.settings_not_chosen)
            ComputerLine.NOT_CONNECTED -> getString(R.string.settings_not_connected, d?.name ?: "")
            ComputerLine.LOADING -> getString(R.string.settings_loading)
            ComputerLine.LOADED -> {
                val name = s!!.desktopName.ifBlank { d?.name ?: "" }
                s.desktopVersion?.takeIf { it.isNotBlank() }?.let { getString(R.string.settings_computer, name, it) }
                    ?: getString(R.string.settings_computer_no_version, name)
            }
            ComputerLine.NOT_LOADED -> getString(R.string.settings_not_loaded, d?.name ?: "")
        }
        val editable = SettingsModel.editable(d != null, link.connected, s)
        renderFixes(s?.wordFixes ?: emptyMap(), editable && !savingFixes)
        enable(addFix, editable && !savingFixes)
        renderShortcuts(s, editable && !savingShortcuts)
        enable(speedBar, editable)
        enable(accelBar, editable)
    }

    private fun renderFixes(fixes: Map<String, String>, enabled: Boolean) {
        if (fixes == shownFixes && enabled == shownEnabled) return
        shownFixes = fixes
        shownEnabled = enabled
        fixList.removeAllViews()
        for ((heard, write) in SettingsModel.rows(fixes)) {
            val row = layoutInflater.inflate(R.layout.settings_fix_row, fixList, false)
            val text = row.findViewById<TextView>(R.id.fix_text)
            val delete = row.findViewById<TextView>(R.id.fix_delete)
            text.text = getString(R.string.settings_fix_row, heard, write)
            text.setOnClickListener { editFix(heard) }
            delete.setOnClickListener { confirmDelete(heard) }
            delete.contentDescription = "${getString(R.string.settings_fix_delete)} $heard"
            enable(text, enabled)
            enable(delete, enabled)
            fixList.addView(row)
        }
        fixesEmpty.visibility = if (fixes.isEmpty() && settings != null) View.VISIBLE else View.GONE
    }

    /** The rows in the computer's order; for a Caw Desktop without shortcuts (older than 0.3), a line saying so. */
    private fun renderShortcuts(s: DesktopSettings?, enabled: Boolean) {
        val list = s?.shortcuts
        val unsupported = s != null && list == null
        shortcutsEmpty.setText(if (unsupported) R.string.settings_shortcuts_unsupported else R.string.settings_shortcuts_empty)
        shortcutsEmpty.visibility = if (unsupported || list?.isEmpty() == true) View.VISIBLE else View.GONE
        addShortcut.visibility = if (unsupported) View.GONE else View.VISIBLE
        enable(addShortcut, enabled && list != null)
        if (shownShortcuts == list to enabled) return
        shownShortcuts = list to enabled
        shortcutList.removeAllViews()
        val shortcuts = list.orEmpty()
        for ((i, shortcut) in shortcuts.withIndex()) {
            val row = layoutInflater.inflate(R.layout.settings_shortcut_row, shortcutList, false)
            val text = row.findViewById<TextView>(R.id.shortcut_text)
            val up = row.findViewById<TextView>(R.id.shortcut_up)
            val down = row.findViewById<TextView>(R.id.shortcut_down)
            val delete = row.findViewById<TextView>(R.id.shortcut_delete)
            text.text = shortcutRow(shortcut)
            text.setOnClickListener { editShortcut(i) }
            up.setOnClickListener { saveShortcuts(SettingsModel.moved(shortcuts, i, -1)) }
            down.setOnClickListener { saveShortcuts(SettingsModel.moved(shortcuts, i, +1)) }
            delete.setOnClickListener { confirmDeleteShortcut(i) }
            up.contentDescription = "${getString(R.string.settings_shortcut_up)} ${shortcut.name}"
            down.contentDescription = "${getString(R.string.settings_shortcut_down)} ${shortcut.name}"
            delete.contentDescription = "${getString(R.string.settings_fix_delete)} ${shortcut.name}"
            enable(text, enabled)
            enable(up, enabled && i > 0)
            enable(down, enabled && i < shortcuts.size - 1)
            enable(delete, enabled)
            shortcutList.addView(row)
        }
    }

    /** "Terminal · Super+Enter" */
    private fun shortcutRow(shortcut: Shortcut): String =
        getString(R.string.settings_shortcut_row, shortcut.name, ShortcutKeys.combo(shortcut))

    private fun enable(v: View, on: Boolean) {
        v.isEnabled = on
        v.alpha = if (on) 1f else 0.4f
    }

    /** Puts [s]'s speed and acceleration on the sliders (the defaults while nothing is loaded). */
    private fun showPointer(s: DesktopSettings?) {
        val speed = s?.pointerSpeed ?: SettingsLimits.SPEED_DEFAULT
        val accel = s?.pointerAccel ?: SettingsLimits.ACCEL_DEFAULT
        speedBar.progress = SettingsModel.speedProgress(speed)
        accelBar.progress = SettingsModel.accelProgress(accel)
        // The computer's own values, even between two slider steps; a dash until they are loaded.
        speedValue.text = if (s == null) "—" else SettingsModel.speedText(speed)
        accelValue.text = if (s == null) "—" else SettingsModel.accelText(accel)
    }

    private fun status(view: TextView, text: String?, warning: Boolean = true) {
        if (view === fixesStatus) loadProblemShown = false
        view.text = text ?: ""
        view.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(if (warning && text != null) R.drawable.ic_warning else 0, 0, 0, 0)
    }

    // ---- word fixes ----

    /** The add (null) / edit dialog: "When it hears" and "Write". */
    private fun editFix(previous: String?) {
        val s = settings ?: return
        val view = layoutInflater.inflate(R.layout.dialog_word_fix, null)
        val heard = view.findViewById<EditText>(R.id.heard)
        val write = view.findViewById<EditText>(R.id.write)
        val problem = view.findViewById<TextView>(R.id.dialog_problem)
        if (previous != null) {
            heard.setText(previous)
            write.setText(s.wordFixes[previous] ?: "")
        }
        val d = AlertDialog.Builder(this)
            .setTitle(if (previous == null) R.string.settings_fix_add else R.string.settings_fix_edit)
            .setView(view)
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        d.setOnShowListener {
            // Save checks first and keeps the dialog open with the reason if something is wrong.
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = heard.text.toString()
                val w = write.text.toString()
                val bad = SettingsLimits.fixProblem(h, w)
                if (bad != null) {
                    status(problem, bad)
                    return@setOnClickListener
                }
                d.dismiss()
                val current = settings ?: return@setOnClickListener
                saveFixes(SettingsModel.withFix(current.wordFixes, previous, h, w))
            }
        }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
        heard.requestFocus()
    }

    private fun confirmDelete(heard: String) {
        val s = settings ?: return
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.settings_fix_delete_title)
            .setMessage(getString(R.string.settings_fix_row, heard, s.wordFixes[heard] ?: ""))
            .setPositiveButton(R.string.settings_fix_delete) { _, _ ->
                settings?.let { saveFixes(SettingsModel.withoutFix(it.wordFixes, heard)) }
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    /** Sends the WHOLE edited list; the rows change only when the computer acknowledges it. */
    private fun saveFixes(fixes: Map<String, String>) {
        val change = SettingsChange(wordFixes = fixes)
        SettingsLimits.problem(change)?.let {
            status(fixesStatus, it)
            return
        }
        savingFixes = true
        status(fixesStatus, getString(R.string.settings_saving), warning = false)
        render()
        RemoteLink.setSettings(change) { reply ->
            if (isDestroyed) return@setSettings
            savingFixes = false
            when (reply) {
                SettingsReply.Saved -> {
                    settings = settings?.copy(wordFixes = fixes)
                    status(fixesStatus, getString(R.string.settings_saved, computerName()), warning = false)
                }
                else -> status(fixesStatus, failureText(reply))
            }
            render()
            if (reply == SettingsReply.NoAnswer && link.connected) load()
        }
    }

    private fun failureText(reply: SettingsReply): String = when (reply) {
        is SettingsReply.Refused -> getString(R.string.settings_refused, reply.reason)
        SettingsReply.NotConnected -> getString(R.string.settings_change_not_connected, link.desktop?.name ?: computerName())
        else -> getString(R.string.settings_no_answer, computerName())
    }

    // ---- shortcuts (v4) ----

    /** The add (null) / edit dialog: name, the keys to hold (Super, Ctrl, Alt, Shift) and the key. */
    private fun editShortcut(index: Int?) {
        val list = settings?.shortcuts ?: return
        val view = layoutInflater.inflate(R.layout.dialog_shortcut, null)
        val name = view.findViewById<EditText>(R.id.shortcut_name)
        val mods = mapOf(
            "super" to view.findViewById<CheckBox>(R.id.mod_super),
            "ctrl" to view.findViewById<CheckBox>(R.id.mod_ctrl),
            "alt" to view.findViewById<CheckBox>(R.id.mod_alt),
            "shift" to view.findViewById<CheckBox>(R.id.mod_shift),
        )
        val keyPicker = view.findViewById<Spinner>(R.id.shortcut_key)
        val problem = view.findViewById<TextView>(R.id.dialog_problem)
        val keys = ShortcutKeys.PICKER
        keyPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, keys.map { ShortcutKeys.label(it) }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        index?.let { list.getOrNull(it) }?.let { old ->
            name.setText(old.name)
            for ((mod, box) in mods) box.isChecked = mod in old.mods
            keyPicker.setSelection(keys.indexOf(old.key).coerceAtLeast(0))
        }
        val d = AlertDialog.Builder(this)
            .setTitle(if (index == null) R.string.settings_shortcut_add else R.string.settings_shortcut_edit)
            .setView(view)
            .setPositiveButton(R.string.settings_save, null)
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        d.setOnShowListener {
            // Save checks first and keeps the dialog open with the reason if something is wrong.
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString()
                val bad = SettingsLimits.shortcutProblem(n)
                if (bad != null) {
                    status(problem, bad)
                    return@setOnClickListener
                }
                d.dismiss()
                val current = settings?.shortcuts ?: return@setOnClickListener
                val shortcut = Shortcut(n, keys[keyPicker.selectedItemPosition.coerceIn(keys.indices)], mods.filterValues { it.isChecked }.keys.toList())
                saveShortcuts(SettingsModel.withShortcut(current, index, shortcut))
            }
        }
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
        name.requestFocus()
    }

    private fun confirmDeleteShortcut(index: Int) {
        val shortcut = settings?.shortcuts?.getOrNull(index) ?: return
        val d = AlertDialog.Builder(this)
            .setTitle(R.string.settings_shortcut_delete_title)
            .setMessage(shortcutRow(shortcut))
            .setPositiveButton(R.string.settings_fix_delete) { _, _ ->
                settings?.shortcuts?.let { saveShortcuts(SettingsModel.withoutShortcut(it, index)) }
            }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        d.setOnDismissListener { if (dialog === d) dialog = null }
        dialog = d
        d.show()
    }

    /** Sends the WHOLE edited list, as for word fixes; the rows change only when the computer acknowledges it. */
    private fun saveShortcuts(shortcuts: List<Shortcut>) {
        val change = SettingsChange(shortcuts = shortcuts)
        SettingsLimits.problem(change)?.let {
            status(shortcutsStatus, it)
            return
        }
        savingShortcuts = true
        status(shortcutsStatus, getString(R.string.settings_saving), warning = false)
        render()
        RemoteLink.setSettings(change) { reply ->
            if (isDestroyed) return@setSettings
            savingShortcuts = false
            when (reply) {
                SettingsReply.Saved -> {
                    settings = settings?.copy(shortcuts = shortcuts)
                    status(shortcutsStatus, getString(R.string.settings_saved, computerName()), warning = false)
                }
                else -> status(shortcutsStatus, failureText(reply))
            }
            render()
            if (reply == SettingsReply.NoAnswer && link.connected) load()
        }
    }

    // ---- pointer ----

    /** Sends one slider's value when the finger lets go; on a refusal the slider goes back. */
    private fun sendPointer(change: SettingsChange) {
        val s = settings ?: return
        val same = (change.pointerSpeed ?: s.pointerSpeed) == s.pointerSpeed && (change.pointerAccel ?: s.pointerAccel) == s.pointerAccel
        if (same) {
            showPointer(s)
            return
        }
        status(pointerStatus, null)
        RemoteLink.setSettings(change) { reply ->
            if (isDestroyed) return@setSettings
            val now = settings ?: return@setSettings
            if (reply == SettingsReply.Saved) {
                settings = now.copy(
                    pointerSpeed = change.pointerSpeed ?: now.pointerSpeed,
                    pointerAccel = change.pointerAccel ?: now.pointerAccel,
                )
                status(pointerStatus, getString(R.string.settings_saved, computerName()), warning = false)
            } else {
                status(pointerStatus, failureText(reply))
            }
            showPointer(settings)
        }
    }

    /**
     * Shows the value while the slider moves and sends it once, when the finger lets go. A change
     * that does not come from dragging (keyboard, accessibility) is sent 600 ms after the last step.
     */
    private inner class Slider(private val show: (Int) -> Unit, private val send: (Int) -> Unit) : SeekBar.OnSeekBarChangeListener {
        private var dragging = false
        private var pending: Runnable? = null

        override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
            if (!fromUser) return
            show(progress)
            if (dragging) return
            pending?.let { main.removeCallbacks(it) }
            pending = Runnable { pending = null; send(bar.progress) }.also { main.postDelayed(it, 600) }
        }

        override fun onStartTrackingTouch(bar: SeekBar) {
            dragging = true
            pending?.let { main.removeCallbacks(it) }
            pending = null
        }

        override fun onStopTrackingTouch(bar: SeekBar) {
            dragging = false
            send(bar.progress)
        }
    }
}
