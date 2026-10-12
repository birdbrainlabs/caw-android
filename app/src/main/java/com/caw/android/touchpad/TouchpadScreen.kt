package com.caw.android.touchpad

import android.os.Handler
import android.os.Looper
import com.caw.android.dictation.Dictation
import com.caw.android.dictation.DictationScreen
import com.caw.android.dictation.FinishedText
import com.caw.android.session.Status
import com.caw.android.session.StatusKind
import com.caw.android.ui.CrumbHost

/**
 * The touchpad screen's hold-to-talk side, process-wide (like [DictationScreen]) so a hold that
 * finishes while [com.caw.android.TouchpadActivity] is being recreated still reaches the computer.
 * Its finished text goes to the paired computer through [Dictation.touchpadText] (switch or not);
 * with no computer paired it lands in the dictation screen's transcript instead.
 *
 * Main thread only.
 */
object TouchpadScreen : Dictation.Front {

    private const val STATUS_RESET_MS = 6_000L

    interface View {
        fun renderStatus()
        fun refreshOrb()
    }

    var view: View? = null

    /** The hold's status (statuses and words exactly as on the dictation screen). */
    var status: Status = Status.READY
        private set

    /** The microphone was asked for once this process from here. */
    var askedForMic = false

    private val main = Handler(Looper.getMainLooper())
    private val statusReset = Runnable { if (!Dictation.activeFor(this)) showStatus(Status.READY) }

    override fun showStatus(status: Status) {
        this.status = status
        main.removeCallbacks(statusReset)
        // As on the dictation screen: warnings and notes go back to the ready text after 6 s.
        if (status.kind != StatusKind.READY && status != Status.TRANSCRIPT_READY) main.postDelayed(statusReset, STATUS_RESET_MS)
        view?.renderStatus()
        view?.refreshOrb()
    }

    override fun deliver(cleaned: String) {
        val routed = Dictation.touchpadText.dispatch(cleaned, FinishedText.Local.ONLY_WHEN_NOT_SENDING) {
            DictationScreen.transcript.append(it)
        }
        if (routed.local) DictationScreen.view?.renderTranscript()
        CrumbHost.delivered() // Crumb celebrates
        showStatus(Status.READY)
    }

    override fun refreshOrb() {
        view?.refreshOrb()
    }

    /** Tapping a warning dismisses it. */
    fun statusTapped() {
        if (!Dictation.activeFor(this) && status.kind == StatusKind.WARNING) showStatus(Status.READY)
    }
}
