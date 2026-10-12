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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.caw.android.dictation.Dictation
import com.caw.android.dictation.DictationScreen
import com.caw.android.remote.RemoteLink
import com.caw.android.session.Status
import com.caw.android.session.StatusKind
import com.caw.android.speech.SpeechEngine
import com.caw.android.ui.CrumbHost
import com.caw.android.ui.CrumbView

/**
 * Caw's main screen: Caw is a dictation app first (the IME pill is the secondary way, for typing
 * into another app's text box). Hold Crumb (or volume-down) and talk; each finished hold is added
 * to the transcript, with the same timing, status words and spacing as the IME pill. Copy puts the
 * whole transcript on the clipboard, Clear empties it. While Send to computer is on, every finished
 * text also goes to the paired computer, and the computer's name, its connection and Backspace /
 * Enter keys show; the Computer row opens [ComputerActivity] to pair or switch it, and Touchpad
 * opens [TouchpadActivity] for the paired computer.
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

        transcriptView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollToEndIfWanted() }
        copyButton.setOnClickListener { copy() }
        clearButton.setOnClickListener { screen.clear() }
        statusView.setOnClickListener { screen.statusTapped() }
        computerRow.setOnClickListener { startActivity(Intent(this, ComputerActivity::class.java)) }
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
        screen.linkChanged()
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
