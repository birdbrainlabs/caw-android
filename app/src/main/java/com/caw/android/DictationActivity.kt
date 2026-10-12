package com.caw.android

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.caw.android.dictation.Dictation
import com.caw.android.dictation.DictationScreen
import com.caw.android.remote.RemoteLink
import com.caw.android.remote.Shortcut
import com.caw.android.session.Status
import com.caw.android.session.StatusKind
import com.caw.android.speech.SpeechEngine
import com.caw.android.ui.CrumbHost
import com.caw.android.ui.CrumbView
import com.caw.android.ui.ShortcutButtons

/**
 * Caw's main screen: Caw is a dictation app first (the IME pill is the secondary way, for typing
 * into another app's text box). Hold Crumb (or volume-down) and talk; each finished hold is added
 * to the transcript, with the same timing, status words and spacing as the IME pill. Copy puts the
 * whole transcript on the clipboard, Clear empties it. While Send to computer is on, every finished
 * text also goes to the paired computer, and the computer's name, its connection and Backspace /
 * Enter keys show; the Computer row opens [ComputerActivity] to pair or switch it, Touchpad
 * opens [TouchpadActivity] for the paired computer, and Settings opens [SettingsActivity].
 *
 * While Send to computer is on, the transcript and Copy / Clear give way to one line with the last
 * text sent (so a mishearing can be seen) and the computer's shortcut buttons (protocol v4); the
 * transcript keeps filling underneath. A computer whose Caw Desktop has no shortcuts gets neither
 * grid nor hint.
 *
 * The hold is [Dictation]'s (shared with the IME); the transcript and status live in
 * [DictationScreen], which outlives this activity across rotation.
 */
class DictationActivity : Activity(), DictationScreen.View, RemoteLink.Observer {

    private val screen = DictationScreen

    private lateinit var transcriptScroll: ScrollView
    private lateinit var transcriptView: TextView
    private lateinit var copyButton: View
    private lateinit var clearButton: View
    private lateinit var statusView: TextView
    private lateinit var crumbSlot: ViewGroup
    private lateinit var spinner: ProgressBar
    private lateinit var computerRow: TextView
    private lateinit var remoteKeys: View
    private lateinit var touchpadButton: View
    private lateinit var shortcutPanel: View
    private lateinit var sentLine: TextView
    private lateinit var shortcutScroll: View
    private lateinit var shortcutGrid: LinearLayout
    private lateinit var shortcutsHint: View
    /** The shortcuts the grid shows (rebuilt only when they change). */
    private var shownShortcuts: List<Shortcut>? = null
    private var crumb: CrumbView? = null
    private var volumeHeld = false
    private var scrollToEnd = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dictation)
        // Once per process: word fixes, the model (starts loading now), Send to computer.
        Dictation.init(this)
        // The process died while Caw was in the background: bring the transcript back.
        val saved = savedInstanceState?.getString(KEY_TRANSCRIPT)
        if (saved != null && screen.transcript.isEmpty) screen.transcript.restore(saved)

        transcriptScroll = findViewById(R.id.transcript_scroll)
        transcriptView = findViewById(R.id.transcript)
        copyButton = findViewById(R.id.copy)
        clearButton = findViewById(R.id.clear)
        statusView = findViewById(R.id.status)
        crumbSlot = findViewById(R.id.crumb_slot)
        spinner = findViewById(R.id.spinner)
        computerRow = findViewById(R.id.computer_row)
        remoteKeys = findViewById(R.id.remote_keys)
        touchpadButton = findViewById(R.id.touchpad)
        shortcutPanel = findViewById(R.id.shortcut_panel)
        sentLine = findViewById(R.id.sent_line)
        shortcutScroll = findViewById(R.id.shortcut_scroll)
        shortcutGrid = findViewById(R.id.shortcut_grid)
        shortcutsHint = findViewById(R.id.shortcuts_hint)

        transcriptView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollToEndIfWanted() }
        copyButton.setOnClickListener { copy() }
        clearButton.setOnClickListener { screen.clear() }
        statusView.setOnClickListener { screen.statusTapped() }
        computerRow.setOnClickListener { startActivity(Intent(this, ComputerActivity::class.java)) }
        findViewById<View>(R.id.settings).setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        // The touchpad needs a paired computer; without one the button opens the pairing screen.
        touchpadButton.setOnClickListener {
            val target = if (RemoteLink.state.desktop != null) TouchpadActivity::class.java else ComputerActivity::class.java
            startActivity(Intent(this, target))
        }
        findViewById<View>(R.id.remote_backspace).setOnClickListener { Dictation.sendKey("backspace") }
        findViewById<View>(R.id.remote_enter).setOnClickListener { Dictation.sendKey("enter") }
    }

    override fun onStart() {
        super.onStart()
        // Once per process (retried next time if Android refuses): keeps the model loaded.
        KeepAliveService.startOnce(this)
        crumb = CrumbHost.attach(crumbSlot).also { c ->
            c.onHold = { down -> if (down) holdDown() else holdUp() }
        }
        screen.view = this
        Dictation.attach(screen)
        RemoteLink.addObserver(this)
        if (RemoteLink.state.sending) RemoteLink.kick()
        if (!hasMicPermission()) {
            if (!screen.askedForMic) {
                screen.askedForMic = true
                screen.showStatus(Status.REQUESTING_PERMISSIONS)
                requestMicPermission()
            } else {
                screen.showStatus(Status.MIC_PERMISSION_REQUIRED)
            }
        }
        renderTranscript()
        renderStatus()
        refreshOrb()
    }

    override fun onResume() {
        super.onResume()
        // Back from the permission prompt with the microphone allowed.
        val waitingForMic = screen.status == Status.REQUESTING_PERMISSIONS || screen.status == Status.MIC_PERMISSION_REQUIRED
        if (waitingForMic && hasMicPermission()) screen.showStatus(Status.READY)
    }

    override fun onStop() {
        // Leaving mid-hold counts as letting go, so nothing heard is lost; the text still lands
        // in the transcript (DictationScreen) when it is finished.
        Dictation.release(screen)
        volumeHeld = false
        Dictation.detach(screen)
        RemoteLink.removeObserver(this)
        if (screen.view === this) screen.view = null
        // Crumb stays loaded in CrumbHost for the IME pill or the next start.
        CrumbHost.detach(crumbSlot)
        crumb = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_TRANSCRIPT, screen.transcript.text)
    }

    // ---- hold to talk ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0 && !volumeHeld) {
                volumeHeld = true
                holdDown()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && volumeHeld) {
            volumeHeld = false
            holdUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun holdDown() {
        if (!hasMicPermission()) {
            // This press only asks for permission; recording needs a fresh hold.
            screen.showStatus(Status.REQUESTING_PERMISSIONS)
            requestMicPermission()
            return
        }
        Dictation.press(screen)
        refreshOrb()
    }

    private fun holdUp() {
        Dictation.release(screen)
        refreshOrb()
    }

    // ---- buttons ----

    private fun copy() {
        val text = screen.transcript.copyText()
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.dictation_clip_label), text))
        screen.showStatus(Status.COPIED)
    }

    // ---- DictationScreen.View ----

    override fun renderStatus() {
        val shown = screen.status
        statusView.text = shown.text
        val icon = if (shown.kind == StatusKind.WARNING) R.drawable.ic_warning else 0
        statusView.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    override fun renderTranscript() {
        val text = screen.transcript.text
        transcriptView.text = text
        val has = !screen.transcript.isEmpty
        copyButton.isEnabled = has
        clearButton.isEnabled = has
        copyButton.alpha = if (has) 1f else 0.4f
        clearButton.alpha = if (has) 1f else 0.4f
        // Keep the newest words in view (once the new text is laid out).
        scrollToEnd = true
        transcriptScroll.post { transcriptScroll.scrollTo(0, transcriptView.height) }
        // One line, whitespace folded, so a long text ends in "…" instead of wrapping.
        sentLine.text = Dictation.lastSent?.replace(Regex("\\s+"), " ")?.let { getString(R.string.dictation_sent, it) }
            ?: getString(R.string.dictation_sent_nothing)
    }

    /** After the transcript's text view is laid out with new text (its height changed). */
    private fun scrollToEndIfWanted() {
        if (!scrollToEnd) return
        scrollToEnd = false
        transcriptScroll.post { transcriptScroll.scrollTo(0, transcriptView.height) }
    }

    /** Pushes the current state to Crumb, while he is on this screen. */
    override fun refreshOrb() {
        val c = crumb?.takeIf { it.parent === crumbSlot } ?: return
        val finishing = CrumbHost.show(c, Dictation.phaseFor(screen), SpeechEngine.isLoaded, holding = false, level = Dictation.micLevel)
        // Crumb shows "thinking" himself; the spinner is only for the plain fallback orb.
        spinner.visibility = if (finishing && c.isFallback) View.VISIBLE else View.GONE
    }

    // ---- RemoteLink.Observer ----

    override fun onState(state: RemoteLink.State) {
        val d = state.desktop
        computerRow.text = when {
            d == null -> getString(R.string.dictation_computer_none)
            !state.enabled -> getString(R.string.dictation_computer_off, d.name)
            state.connected -> getString(R.string.dictation_computer_connected, d.name)
            state.waiting > 0 -> getString(R.string.dictation_computer_waiting, d.name, state.waiting)
            else -> getString(R.string.dictation_computer_away, d.name)
        }
        remoteKeys.visibility = if (state.sending) View.VISIBLE else View.GONE
        touchpadButton.alpha = if (d != null) 1f else 0.4f
        renderShortcuts(state)
        screen.linkChanged()
    }

    /** Send to computer on: the last text sent and the shortcut grid stand in for the transcript. */
    private fun renderShortcuts(state: RemoteLink.State) {
        val sending = state.sending
        val transcriptShown = if (sending) View.GONE else View.VISIBLE
        transcriptScroll.visibility = transcriptShown
        copyButton.visibility = transcriptShown
        clearButton.visibility = transcriptShown
        shortcutPanel.visibility = if (sending) View.VISIBLE else View.GONE
        val list = state.shortcuts
        // null: the computer's Caw Desktop has no shortcuts (or Caw has not heard yet): no grid, no hint.
        shortcutScroll.visibility = if (list.isNullOrEmpty()) View.GONE else View.VISIBLE
        shortcutsHint.visibility = if (list != null && list.isEmpty()) View.VISIBLE else View.GONE
        if (list == shownShortcuts) return
        shownShortcuts = list
        shortcutGrid.removeAllViews()
        val columns = resources.getInteger(R.integer.shortcut_columns)
        val gap = (4 * resources.displayMetrics.density).toInt()
        for (items in list.orEmpty().chunked(columns)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until columns) {
                // A short last row keeps the same column widths.
                val cell = items.getOrNull(i)?.let { ShortcutButtons.make(layoutInflater, row, it, ::shortcutNotSent) } ?: View(this)
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(gap, gap, gap, gap) })
            }
            shortcutGrid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** A shortcut tapped while the computer is not connected: never sent later, so say so. */
    private fun shortcutNotSent() {
        if (Dictation.activeFor(screen)) return
        val link = RemoteLink.state
        screen.showStatus(Status.notConnected(link.desktop?.name ?: "the computer", link.waiting))
    }

    override fun onRejected(reason: String) {
        if (Dictation.activeFor(screen)) return
        screen.showStatus(Status.computerRefused(RemoteLink.state.desktop?.name ?: "The computer", reason))
    }

    // ---- permission ----

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** PermissionActivity asks for the microphone (and notifications, for the "Caw is ready" notice). */
    private fun requestMicPermission() {
        startActivity(Intent(this, PermissionActivity::class.java))
    }

    companion object {
        private const val KEY_TRANSCRIPT = "transcript"
    }
}
