package com.caw.android.dictation

import android.os.Handler
import android.os.Looper
import com.caw.android.remote.RemoteLink
import com.caw.android.session.Status
import com.caw.android.session.StatusKind
import com.caw.android.ui.CrumbHost

/**
 * The dictation screen's state, process-wide so a hold that finishes while the activity is being
 * recreated (rotation) or is briefly in the background still lands in the transcript. The
 * activity ([com.caw.android.DictationActivity]) only shows it, through [view].
 *
 * Main thread only.
 */
object DictationScreen : Dictation.Front {

    private const val STATUS_RESET_MS = 6_000L
    private const val COPIED_MS = 2_000L

    /** What the activity on screen draws. */
    interface View {
        fun renderStatus()
        fun renderTranscript()
        fun refreshOrb()
    }

    val transcript = Transcript()

    var view: View? = null

    /** The status shown (the ready text already swapped for its Send-to-computer stand-in). */
    var status: Status = Status.READY
        private set

    /** The status shown is the ready text, so it follows the computer link. */
    private var showingReady = true

    /** The microphone was asked for once this process; afterwards the screen just says it is needed. */
    var askedForMic = false

    /** What Clear removed, while "Cleared — tap here to undo" is showing. */
    private var undoText: String? = null

    private val main = Handler(Looper.getMainLooper())
    private val statusReset = Runnable { if (!Dictation.activeFor(this)) showStatus(Status.READY) }

    override fun showStatus(status: Status) {
        showingReady = status == Status.READY
        val link = RemoteLink.state
        val shown = if (showingReady) Status.readyFor(link.desktop?.name, link.sending, link.connected, link.waiting) else status
        this.status = shown
        if (shown != Status.CLEARED) undoText = null
        main.removeCallbacks(statusReset)
        // As on the IME pill: warnings and notes go back to the ready text after 6 s ("Copied" sooner).
        if (!showingReady && shown.kind != StatusKind.READY && shown != Status.TRANSCRIPT_READY) {
            main.postDelayed(statusReset, if (shown == Status.COPIED) COPIED_MS else STATUS_RESET_MS)
        }
        view?.renderStatus()
        view?.refreshOrb()
    }

    /** The computer link changed: the ready text may name it differently. */
    fun linkChanged() {
        if (showingReady && !Dictation.activeFor(this)) showStatus(Status.READY)
    }

    /** Every finished text goes into the transcript, and also to the computer while Send to computer is on. */
    override fun deliver(cleaned: String) {
        Dictation.finishedText.dispatch(cleaned, FinishedText.Local.ALWAYS) { transcript.append(it) }
        CrumbHost.delivered() // Crumb celebrates
        showStatus(Status.READY)
        view?.renderTranscript()
    }

    override fun refreshOrb() {
        view?.refreshOrb()
    }

    /** Empties the transcript; "Cleared — tap here to undo" brings it back until anything else happens. */
    fun clear() {
        if (transcript.isEmpty || Dictation.activeFor(this)) return
        undoText = transcript.clear()
        showStatus(Status.CLEARED)
        view?.renderTranscript()
    }

    /** Tapping the status: undoes a Clear, or dismisses a warning. */
    fun statusTapped() {
        if (Dictation.activeFor(this)) return
        val undo = undoText
        when {
            status == Status.CLEARED && undo != null -> {
                transcript.restore(undo)
                showStatus(Status.READY)
                view?.renderTranscript()
            }
            status.kind == StatusKind.WARNING && !showingReady -> showStatus(Status.READY)
        }
    }
}
