package com.caw.android.ui

import android.content.Context
import android.provider.Settings
import android.view.ViewGroup
import com.caw.android.R
import com.caw.android.session.HoldSession

/**
 * Process-wide owner of [CrumbView], like SpeechEngine for the model. Android destroys the IME
 * service each time the user goes back to their keyboard; the WebView lives here (on the
 * application context) so Crumb is already loaded the next time Caw is shown. It is never
 * destroyed, only moved between panels.
 *
 * Main thread only.
 */
object CrumbHost {

    private var view: CrumbView? = null

    /**
     * Successful insertions this process. Kept here, not in the service, because the page outlives
     * the service and only cheers when the number goes UP from the last one it saw.
     */
    var deliveries = 0
        private set

    fun delivered() {
        deliveries++
    }

    /** Puts Crumb into [slot] (under any views already there), taking him out of an old panel. */
    fun attach(slot: ViewGroup): CrumbView {
        val v = view ?: CrumbView(slot.context.applicationContext).also { view = it }
        (v.parent as? ViewGroup)?.removeView(v)
        slot.addView(
            v,
            0,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return v
    }

    /**
     * Takes Crumb out of [slot] (so the old panel and service can be freed) and keeps him loaded.
     * Does nothing if a newer panel already has him.
     */
    fun detach(slot: ViewGroup) {
        val v = view ?: return
        if (v.parent !== slot) return
        v.onHold = null
        slot.removeView(v)
    }

    /**
     * Pushes a hold's state to Crumb, the same for the IME pill and
     * the dictation screen. [holding]: finished text is waiting for a text box (IME only).
     * Returns true while the hold is finishing (the plain fallback orb then shows a spinner).
     */
    fun show(c: CrumbView, phase: HoldSession.Phase, modelLoaded: Boolean, holding: Boolean, level: Float): Boolean {
        val finishing = phase == HoldSession.Phase.TAIL || phase == HoldSession.Phase.FINISHING
        val recording = phase == HoldSession.Phase.LISTENING
        val processing = !recording && (finishing || !modelLoaded)
        val waiting = !recording && !processing && holding
        c.update(
            active = recording || processing || waiting,
            recording = recording,
            processing = processing,
            waiting = waiting,
            level = if (recording) level else 0f,
            delivery = deliveries,
            gentle = reduceMotion(c.context),
        )
        c.contentDescription = c.context.getString(
            when {
                recording -> R.string.orb_listening
                finishing -> R.string.orb_finishing
                holding -> R.string.orb_holding
                else -> R.string.orb_idle
            },
        )
        return finishing
    }

    /** The system "remove animations" setting (Developer options / Accessibility). */
    fun reduceMotion(context: Context): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}
