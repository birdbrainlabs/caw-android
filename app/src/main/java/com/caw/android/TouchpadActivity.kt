package com.caw.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import com.caw.android.dictation.Dictation
import com.caw.android.remote.RemoteLink
import com.caw.android.remote.RemoteStore
import com.caw.android.session.Status
import com.caw.android.session.StatusKind
import com.caw.android.speech.SpeechEngine
import com.caw.android.touchpad.TouchGestures
import com.caw.android.touchpad.TouchpadScreen
import com.caw.android.ui.CrumbHost
import com.caw.android.ui.CrumbView

/**
 * The paired computer's touchpad (docs/BUILD.md section 11), opened from the dictation screen's
 * Touchpad button. The touch area's fingers go through [TouchGestures] (which knows nothing of
 * Android) and out as v2 `pointer_move` / `click` / `scroll` messages, fire-and-forget. Crumb
 * under it is the same hold-to-talk as everywhere (the shared [Dictation]); the finished text goes
 * to the computer, with Backspace and Enter next to him. While a hold runs the touch area is
 * ignored. The screen stays on while this is open, and Caw stays connected even with Send to
 * computer off. Back leaves it.
 */
class TouchpadActivity : Activity(), TouchpadScreen.View, RemoteLink.Observer {

    private val screen = TouchpadScreen
    private val main = Handler(Looper.getMainLooper())

    private lateinit var linkView: TextView
    private lateinit var statusView: TextView
    private lateinit var touchArea: View
    private lateinit var crumbSlot: ViewGroup
    private lateinit var spinner: ProgressBar
    private var crumb: CrumbView? = null
    private lateinit var gestures: TouchGestures

    /** Calls [TouchGestures.tick] every 12 ms while a finger is on the pad. */
    private val ticker = object : Runnable {
        override fun run() {
            gestures.tick(SystemClock.uptimeMillis())
            if (gestures.active) main.postDelayed(this, TouchGestures.FLUSH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_touchpad)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Dictation.init(this)

        linkView = findViewById(R.id.link)
        statusView = findViewById(R.id.status)
        touchArea = findViewById(R.id.touch_area)
        crumbSlot = findViewById(R.id.crumb_slot)
        spinner = findViewById(R.id.spinner)

        gestures = TouchGestures(resources.displayMetrics.density, object : TouchGestures.Output {
            override fun move(dx: Double, dy: Double) = RemoteLink.sendMove(dx, dy)
            override fun scroll(dx: Double, dy: Double) = RemoteLink.sendScroll(dx, dy)
            override fun click(button: String, action: String?) = RemoteLink.sendClick(button, action)
            override fun longPressEngaged() {
                touchArea.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        })
        @SuppressLint("ClickableViewAccessibility")
        touchArea.setOnTouchListener { _, e -> onPad(e) }
        statusView.setOnClickListener { screen.statusTapped() }
        findViewById<View>(R.id.remote_backspace).setOnClickListener { Dictation.sendKey("backspace", whenPaired = true) }
        findViewById<View>(R.id.remote_enter).setOnClickListener { Dictation.sendKey("enter", whenPaired = true) }
    }

    override fun onStart() {
        super.onStart()
        crumb = CrumbHost.attach(crumbSlot).also { c ->
            c.onHold = { down -> if (down) holdDown() else holdUp() }
        }
        screen.view = this
        Dictation.attach(screen)
        // Phone-only setting (Settings screen): off = the scroll follows the fingers.
        gestures.naturalScrolling = RemoteStore(this).naturalScrolling
        RemoteLink.addObserver(this)
        RemoteLink.holdOpen(RemoteLink.SCREEN_TOUCHPAD, true)
        if (!hasMicPermission()) {
            if (!screen.askedForMic) {
                screen.askedForMic = true
                screen.showStatus(Status.REQUESTING_PERMISSIONS)
                requestMicPermission()
            } else {
                screen.showStatus(Status.MIC_PERMISSION_REQUIRED)
            }
        }
        renderStatus()
        refreshOrb()
    }

    override fun onResume() {
        super.onResume()
        val waitingForMic = screen.status == Status.REQUESTING_PERMISSIONS || screen.status == Status.MIC_PERMISSION_REQUIRED
        if (waitingForMic && hasMicPermission()) screen.showStatus(Status.READY)
    }

    override fun onStop() {
        // A held button must not stay down on the computer; leaving mid-hold counts as letting go.
        gestures.cancel()
        main.removeCallbacks(ticker)
        Dictation.release(screen)
        Dictation.detach(screen)
        RemoteLink.removeObserver(this)
        RemoteLink.holdOpen(RemoteLink.SCREEN_TOUCHPAD, false)
        if (screen.view === this) screen.view = null
        CrumbHost.detach(crumbSlot)
        crumb = null
        super.onStop()
    }

    // ---- the touch area ----

    private fun onPad(e: MotionEvent): Boolean {
        // While a hold runs, the pad is ignored.
        if (Dictation.activeFor(screen)) return true
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchGestures.Action.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> TouchGestures.Action.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> TouchGestures.Action.MOVE
            MotionEvent.ACTION_POINTER_UP -> TouchGestures.Action.POINTER_UP
            MotionEvent.ACTION_UP -> TouchGestures.Action.UP
            MotionEvent.ACTION_CANCEL -> TouchGestures.Action.CANCEL
            else -> return true
        }
        val pointers = List(e.pointerCount) { i -> TouchGestures.Pointer(e.getPointerId(i), e.getX(i), e.getY(i)) }
        val wasActive = gestures.active
        gestures.onTouch(TouchGestures.Touch(action, e.eventTime, pointers, e.getPointerId(e.actionIndex)))
        if (gestures.active && !wasActive) {
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, TouchGestures.FLUSH_MS)
        }
        return true
    }

    // ---- hold to talk ----

    private fun holdDown() {
        if (!hasMicPermission()) {
            screen.showStatus(Status.REQUESTING_PERMISSIONS)
            requestMicPermission()
            return
        }
        // A gesture under way ends (a held button is released) and the pad waits for the hold.
        gestures.cancel()
        Dictation.press(screen)
        refreshOrb()
    }

    private fun holdUp() {
        Dictation.release(screen)
        refreshOrb()
    }

    // ---- TouchpadScreen.View ----

    override fun renderStatus() {
        show(statusView, screen.status)
    }

    override fun refreshOrb() {
        val c = crumb?.takeIf { it.parent === crumbSlot } ?: return
        val finishing = CrumbHost.show(c, Dictation.phaseFor(screen), SpeechEngine.isLoaded, holding = false, level = Dictation.micLevel)
        spinner.visibility = if (finishing && c.isFallback) View.VISIBLE else View.GONE
    }

    // ---- RemoteLink.Observer ----

    override fun onState(state: RemoteLink.State) {
        show(linkView, Status.touchpadLink(state.desktop?.name, state.connected, state.pointerUnavailable))
    }

    override fun onRejected(reason: String) {
        if (Dictation.activeFor(screen)) return
        screen.showStatus(Status.computerRefused(RemoteLink.state.desktop?.name ?: "The computer", reason))
    }

    private fun show(view: TextView, status: Status) {
        view.text = status.text
        val icon = if (status.kind == StatusKind.WARNING) R.drawable.ic_warning else 0
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    // ---- permission ----

    private fun hasMicPermission(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        startActivity(Intent(this, PermissionActivity::class.java))
    }
}
