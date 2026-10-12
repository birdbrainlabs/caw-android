package com.caw.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ProgressBar
import android.widget.TextView
import com.caw.android.dictation.Dictation
import com.caw.android.dictation.FinishedText
import com.caw.android.remote.RemoteLink
import com.caw.android.session.Status
import com.caw.android.session.StatusKind
import com.caw.android.speech.SpeechEngine
import com.caw.android.text.CommitChunks
import com.caw.android.text.TextPipeline
import com.caw.android.ui.CrumbHost
import com.caw.android.ui.CrumbView

/**
 * Caw as a voice input method: the secondary way to dictate, straight into another app's text box
 * (Caw is a dictation app first; its own screen is [DictationActivity]). A keyboard's mic key
 * (e.g. HeliBoard) switches to it; holding the orb (or volume-down) records; on release the text
 * goes into the field and Caw switches back.
 *
 * The hold itself (HoldSession, mic, recognizer, cleaner) is [Dictation], shared with the
 * dictation screen; this pill is one of its fronts. The model lives in [SpeechEngine], which
 * outlives this service.
 *
 * "Send to computer" (docs/remote-protocol.md): while that switch is on and a computer is paired,
 * the finished text goes to the computer ([FinishedText], [RemoteLink]) instead of into the phone's field,
 * Caw stays up (no switch back), and the pill shows the computer's name plus Backspace / Enter.
 */
class CawInputMethodService : InputMethodService(), Dictation.Front {

    private val main = Handler(Looper.getMainLooper())
    private val engine = SpeechEngine

    /** Crumb, the hold-to-talk orb. Lives in [CrumbHost]; this panel only borrows him. */
    private var crumb: CrumbView? = null
    private var crumbSlot: ViewGroup? = null
    private var spinner: ProgressBar? = null
    private var statusView: TextView? = null
    private var status: Status = Status.READY
    /** The status shown is the ready text (or its Send-to-computer stand-in), so it follows the link. */
    private var showingReady = true
    private var remoteKeys: View? = null

    private val remoteObserver = object : RemoteLink.Observer {
        override fun onState(state: RemoteLink.State) {
            remoteKeys?.visibility = if (state.sending) View.VISIBLE else View.GONE
            if (showingReady && !Dictation.activeFor(this@CawInputMethodService)) showStatus(Status.READY)
        }

        override fun onTextDelivered() {
            CrumbHost.delivered() // Crumb celebrates when the computer has it
            refreshOrb()
        }

        override fun onRejected(reason: String) {
            if (Dictation.activeFor(this@CawInputMethodService)) return
            showStatus(Status.computerRefused(RemoteLink.state.desktop?.name ?: "The computer", reason))
        }
    }

    /** Text that could not be inserted; waits up to 30 s for a text box (spec 3.1). */
    private var heldText: String? = null
    private var heldAt = 0L
    private var volumeHeld = false
    private var askedOnShow = false

    /** The paced commit in progress, if any (see [commitPaced]). */
    private var pacing: Runnable? = null

    private val statusReset = Runnable { resetStatusIfIdle() }

    override fun onCreate() {
        super.onCreate()
        // Once per process: word fixes, the model (starts loading now), Send to computer.
        Dictation.init(this)
        RemoteLink.addObserver(remoteObserver)
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.panel, null)
        val slot = view.findViewById<ViewGroup>(R.id.crumb_slot)
        crumbSlot = slot
        attachCrumb()
        spinner = view.findViewById(R.id.spinner)
        statusView = view.findViewById<TextView>(R.id.status).also { text ->
            // Tapping a warning dismisses it.
            text.setOnClickListener {
                if (status.kind == StatusKind.WARNING && !Dictation.activeFor(this)) {
                    heldText = null
                    showStatus(Status.READY)
                }
            }
        }
        view.findViewById<View>(R.id.keyboard).setOnClickListener { switchToPreviousInputMethod() }
        remoteKeys = view.findViewById<View>(R.id.remote_keys).also {
            it.visibility = if (RemoteLink.state.sending) View.VISIBLE else View.GONE
        }
        view.findViewById<View>(R.id.remote_backspace).setOnClickListener { Dictation.sendKey("backspace") }
        view.findViewById<View>(R.id.remote_enter).setOnClickListener { Dictation.sendKey("enter") }
        showStatus(if (showingReady) Status.READY else status)
        return view
    }

    /**
     * Puts Crumb into this panel's slot (the dictation screen may have borrowed him since) and
     * points his hold at this pill.
     */
    private fun attachCrumb() {
        val slot = crumbSlot ?: return
        crumb = CrumbHost.attach(slot).also { c ->
            c.onHold = { down -> if (down) holdDown() else holdUp() }
        }
        Dictation.attach(this)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (heldText != null) main.post { insertHeldText() }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Once per process (retried on the next show if Android refuses): keeps the model loaded.
        KeepAliveService.startOnce(this)
        if (crumb?.parent !== crumbSlot) attachCrumb()
        if (RemoteLink.state.sending) RemoteLink.kick()
        if (!hasMicPermission()) {
            if (!askedOnShow) {
                askedOnShow = true
                showStatus(Status.REQUESTING_PERMISSIONS)
                requestMicPermission()
            } else {
                showStatus(Status.MIC_PERMISSION_REQUIRED)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        // The panel went away mid-hold: treat it as letting go, so nothing heard is lost.
        Dictation.release(this)
        volumeHeld = false
        super.onFinishInputView(finishingInput)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && isInputViewShown) {
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

    override fun onDestroy() {
        // The model and its worker stay loaded in SpeechEngine for the next time Caw is shown.
        main.removeCallbacksAndMessages(null)
        Dictation.abandon(this)
        // Crumb stays loaded in CrumbHost for the next panel; only let go of him here.
        crumbSlot?.let { CrumbHost.detach(it) }
        RemoteLink.removeObserver(remoteObserver)
        crumb = null
        crumbSlot = null
        super.onDestroy()
    }

    // ---- hold to talk (Dictation owns the hold; this pill is one of its two fronts) ----

    private fun holdDown() {
        if (!hasMicPermission()) {
            // This press only asks for permission; recording needs a fresh hold.
            showStatus(Status.REQUESTING_PERMISSIONS)
            requestMicPermission()
            return
        }
        Dictation.press(this)
        refreshOrb()
    }

    private fun holdUp() {
        Dictation.release(this)
        refreshOrb()
    }

    // ---- Dictation.Front ----

    override fun showStatus(status: Status) {
        showingReady = status == Status.READY
        val shown = if (showingReady) readyStatus() else status
        this.status = shown
        statusView?.let { text ->
            text.text = shown.text
            val icon = if (shown.kind == StatusKind.WARNING) R.drawable.ic_warning else 0
            text.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
        main.removeCallbacks(statusReset)
        // The ready text's Send-to-computer stand-ins ("Not connected to ...") stay until the link changes.
        if (!showingReady && shown.kind != StatusKind.READY && shown != Status.TRANSCRIPT_READY) {
            main.postDelayed(statusReset, STATUS_RESET_MS)
        }
        refreshOrb()
    }

    /** The ready text: plain, or with the paired computer's name and connection while Send to computer is on. */
    private fun readyStatus(): Status {
        val link = RemoteLink.state
        return Status.readyFor(link.desktop?.name, link.sending, link.connected, link.waiting)
    }

    /**
     * While Send to computer is on the text goes to the computer only (see [FinishedText]) and the
     * phone's field is left alone; Caw stays up for the next hold and the Backspace / Enter keys.
     */
    override fun deliver(cleaned: String) {
        val routed = Dictation.finishedText.dispatch(cleaned, FinishedText.Local.ONLY_WHEN_NOT_SENDING) { text ->
            if (!insert(text)) holdText(text)
        }
        if (routed.computerText != null) {
            heldText = null
            showStatus(Status.READY)
        }
    }

    // ---- Send to computer ----

    // ---- inserting ----

    /**
     * Commits [text] and calls [done] once all of it is in. Returns false if nothing went in, so
     * the caller can hold the text. A paced commit that breaks part-way holds the rest itself.
     */
    private fun insert(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        // One character only, so the lookup stays quick; null means "no text before".
        val before = ic.getTextBeforeCursor(1, 0)
        val committed = TextPipeline.commitString(before, text)
        if (currentInputEditorInfo?.packageName?.let { it in PACED_PACKAGES } == true) {
            Log.i(TAG, "committed: \"$committed\" (paced)")
            return commitPaced(ic, CommitChunks.chunks(committed))
        }
        val ok = ic.commitText(committed, 1)
        Log.i(TAG, "committed: \"$committed\"${if (ok) "" else " (commitText returned false)"}")
        Log.i(TAG, "release-to-commit ms: ${SystemClock.uptimeMillis() - Dictation.releasedAt}")
        if (ok) done()
        return ok
    }

    /**
     * Commits [chunks] one at a time, [PACE_MS] apart, then calls [done]. For apps that replay a
     * whole commit as instant keystrokes and drop letters (KDE Connect). Returns false only if
     * the first chunk is refused; a later failure holds the remaining text for the next field.
     */
    private fun commitPaced(ic: InputConnection, chunks: List<String>): Boolean {
        if (chunks.isEmpty()) {
            done()
            return true
        }
        val started = SystemClock.uptimeMillis()
        var next = 0
        val step = object : Runnable {
            override fun run() {
                val same = currentInputConnection === ic
                if (!same || !ic.commitText(chunks[next], 1)) {
                    pacing = null
                    val why = if (same) "commitText returned false" else "input connection gone"
                    Log.i(TAG, "paced: stopped at chunk ${next + 1} of ${chunks.size} ($why)")
                    if (next > 0) {
                        val rest = chunks.subList(next, chunks.size).joinToString("").trim()
                        if (rest.isEmpty()) done() else holdText(rest)
                    }
                    return
                }
                if (next == 0) {
                    Log.i(TAG, "release-to-commit ms: ${SystemClock.uptimeMillis() - Dictation.releasedAt}")
                }
                next++
                if (next < chunks.size) {
                    main.postDelayed(this, PACE_MS)
                } else {
                    pacing = null
                    Log.i(TAG, "paced: ${chunks.size} chunks, ${SystemClock.uptimeMillis() - started} ms")
                    done()
                }
            }
        }
        pacing = step
        step.run()
        return next > 0
    }

    /** Keeps [text] for up to 30 s so the next text box gets it, and says the insert failed. */
    private fun holdText(text: String) {
        heldText = text
        heldAt = SystemClock.uptimeMillis()
        showStatus(Status.INSERT_FAILED)
    }

    private fun insertHeldText() {
        val text = heldText ?: return
        if (Dictation.activeFor(this) || pacing != null) return
        if (SystemClock.uptimeMillis() - heldAt > HELD_TEXT_MS) {
            heldText = null
            refreshOrb()
            return
        }
        insert(text)
    }

    private fun done() {
        CrumbHost.delivered() // Crumb celebrates
        heldText = null
        showStatus(Status.READY)
        switchToPreviousInputMethod()
    }

    // ---- status and orb ----

    private fun resetStatusIfIdle() {
        if (Dictation.activeFor(this)) return
        if (heldText != null) {
            val waited = SystemClock.uptimeMillis() - heldAt
            if (waited < HELD_TEXT_MS) {
                main.postDelayed(statusReset, HELD_TEXT_MS - waited)
                return
            }
            heldText = null
        }
        showStatus(Status.READY)
    }

    /** Pushes the current state to Crumb (docs/crumb-spec.md section 5). */
    override fun refreshOrb() {
        // Only while Crumb is in this panel: the dictation screen may have borrowed him.
        val c = crumb?.takeIf { it.parent === crumbSlot }
        val finishing = c != null &&
            CrumbHost.show(c, Dictation.phaseFor(this), engine.isLoaded, heldText != null, Dictation.micLevel)
        // Crumb shows "thinking" himself; the spinner is only for the plain fallback orb.
        spinner?.visibility = if (finishing && c?.isFallback == true) View.VISIBLE else View.GONE
    }

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val TAG = "CawSpeech"
        private const val STATUS_RESET_MS = 6_000L
        private const val HELD_TEXT_MS = 30_000L

        /** Apps whose fields get text a word at a time, [PACE_MS] apart (see [commitPaced]). */
        private val PACED_PACKAGES = setOf("org.kde.kdeconnect_tp")
        private const val PACE_MS = 40L
    }
}
