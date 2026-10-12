package com.caw.android.touchpad

import kotlin.math.hypot

/**
 * The touchpad screen's gestures, turned into mouse actions:
 *
 * - one finger moving: the mouse moves by the finger's movement in dp (px / density, raw; the
 *   desktop applies acceleration);
 * - a tap (lifted before [LONG_PRESS_MS] without moving past [SLOP_DP]): left click;
 * - a two-finger tap (one lifts within [TWO_TAP_MS], neither moved): right click;
 * - two fingers moving: scroll, NATURAL by default (the page follows the fingers: fingers up =
 *   scroll down, so the finger movement is sent negated; [naturalScrolling] off sends it as it is);
 * - a long press (a finger still for [LONG_PRESS_MS]) then moving: left button down, moves, and
 *   left button up when the finger lifts (a drag). [Output.longPressEngaged] fires as it engages.
 *
 * Moves and scrolls are summed and sent at most once every [FLUSH_MS] by [tick], which the
 * activity calls on that beat while a finger is down; what is left goes out when the fingers lift.
 * Movement inside the slop before a gesture is decided is not sent on its own: it goes with the
 * first move once the finger passes the slop, and is dropped for a tap or a long press.
 *
 * Pure Kotlin (no Android views), so tests feed it synthetic [Touch]es. Times are milliseconds on
 * one clock (the activity uses MotionEvent.eventTime and SystemClock.uptimeMillis).
 */
class TouchGestures(private val density: Float, private val out: Output) {

    interface Output {
        /** Mouse movement in dp. */
        fun move(dx: Double, dy: Double)

        /** Scroll in dp, positive dy = down (already natural: the negated finger movement). */
        fun scroll(dx: Double, dy: Double)

        /** [button] "left" / "right"; [action] null for a click, "down" / "up" for a drag. */
        fun click(button: String, action: String? = null)

        /** A long press just engaged (left button down): the activity gives a haptic tick. */
        fun longPressEngaged() {}
    }

    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

    data class Pointer(val id: Int, val x: Float, val y: Float)

    /**
     * One touch event, as MotionEvent has it: [pointers] are every finger on the pad (for UP and
     * POINTER_UP still including the one lifting), [changed] is the finger that went down or up.
     */
    data class Touch(val action: Action, val time: Long, val pointers: List<Pointer>, val changed: Int = pointers.first().id)

    private enum class State {
        IDLE,
        /** One finger, not yet a move, a tap or a long press. */
        ONE,
        MOVING,
        /** Long press engaged: the left button is down. */
        HELD,
        /** Two fingers, not yet moved. */
        TWO,
        SCROLLING,
        /** The gesture is over (or not one we know); wait for every finger to lift. */
        DONE,
    }

    companion object {
        const val FLUSH_MS = 12L
        const val LONG_PRESS_MS = 400L
        const val TWO_TAP_MS = 300L
        const val SLOP_DP = 8f
    }

    private val slopPx = SLOP_DP * density

    private var state = State.IDLE
    private var primary = -1
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private var twoIds = intArrayOf(-1, -1)
    private var twoTime = 0L
    private var twoStartX = 0f
    private var twoStartY = 0f
    private var twoLastX = 0f
    private var twoLastY = 0f

    private var moveX = 0.0
    private var moveY = 0.0
    private var scrollX = 0.0
    private var scrollY = 0.0
    private var lastFlush = Long.MIN_VALUE / 2

    /** A finger is on the pad (the activity keeps calling [tick] while this is true). */
    val active: Boolean get() = state != State.IDLE

    fun onTouch(t: Touch) {
        checkLongPress(t.time)
        when (t.action) {
            Action.DOWN -> down(t)
            Action.POINTER_DOWN -> pointerDown(t)
            Action.MOVE -> moved(t)
            Action.POINTER_UP -> pointerUp(t)
            Action.UP -> up(t)
            Action.CANCEL -> cancel()
        }
    }

    /** Every [FLUSH_MS] while [active]: engages a long press when it is due and sends what has summed up. */
    fun tick(now: Long) {
        checkLongPress(now)
        flush(now, force = false)
    }

    /** Stops the gesture (a hold started, the screen went away): a held button is released. */
    fun cancel() {
        if (state == State.HELD) {
            flush(0, force = true)
            out.click("left", "up")
        }
        clearPending()
        state = State.IDLE
    }

    // ---- events ----

    private fun down(t: Touch) {
        if (state == State.HELD) out.click("left", "up") // a lost UP: never leave the button down
        clearPending()
        val p = t.pointers.firstOrNull { it.id == t.changed } ?: t.pointers.first()
        primary = p.id
        downTime = t.time
        startX = p.x
        startY = p.y
        lastX = p.x
        lastY = p.y
        state = State.ONE
    }

    private fun pointerDown(t: Touch) {
        when (state) {
            State.ONE, State.MOVING -> {
                if (state == State.MOVING) flush(t.time, force = true)
                clearPending()
                if (t.pointers.size != 2) {
                    state = State.DONE
                    return
                }
                twoIds = intArrayOf(t.pointers[0].id, t.pointers[1].id)
                twoTime = t.time
                val (cx, cy) = centroid(t) ?: return
                twoStartX = cx
                twoStartY = cy
                twoLastX = cx
                twoLastY = cy
                state = State.TWO
            }
            State.TWO -> state = State.DONE // three fingers: not a gesture
            State.SCROLLING -> {
                flush(t.time, force = true)
                state = State.DONE
            }
            else -> Unit // HELD keeps following its finger; DONE waits
        }
    }

    private fun moved(t: Touch) {
        when (state) {
            State.ONE -> {
                val p = t.pointers.firstOrNull { it.id == primary } ?: return
                if (hypot(p.x - startX, p.y - startY) > slopPx) {
                    state = State.MOVING
                    // The movement inside the slop goes with the first move, so none is lost.
                    addMove(p.x - startX, p.y - startY)
                }
                // Followed even inside the slop, so a long press moves from where the finger is.
                lastX = p.x
                lastY = p.y
            }
            State.MOVING, State.HELD -> {
                val p = t.pointers.firstOrNull { it.id == primary } ?: return
                addMove(p.x - lastX, p.y - lastY)
                lastX = p.x
                lastY = p.y
            }
            State.TWO -> {
                val (cx, cy) = centroid(t) ?: return
                if (hypot(cx - twoStartX, cy - twoStartY) > slopPx) {
                    state = State.SCROLLING
                    addScroll(cx - twoStartX, cy - twoStartY)
                    twoLastX = cx
                    twoLastY = cy
                }
            }
            State.SCROLLING -> {
                val (cx, cy) = centroid(t) ?: return
                addScroll(cx - twoLastX, cy - twoLastY)
                twoLastX = cx
                twoLastY = cy
            }
            else -> Unit
        }
    }

    private fun pointerUp(t: Touch) {
        when (state) {
            State.TWO -> {
                if (t.time - twoTime <= TWO_TAP_MS) out.click("right")
                state = State.DONE
            }
            State.SCROLLING -> {
                flush(t.time, force = true)
                state = State.DONE
            }
            State.HELD -> if (t.changed == primary) {
                flush(t.time, force = true)
                out.click("left", "up")
                state = State.DONE
            }
            else -> Unit
        }
    }

    private fun up(t: Touch) {
        // The lift carries the finger's last position: movement since the last MOVE counts too.
        if (state == State.MOVING || state == State.HELD) {
            t.pointers.firstOrNull { it.id == primary }?.let { p ->
                addMove(p.x - lastX, p.y - lastY)
                lastX = p.x
                lastY = p.y
            }
        }
        when (state) {
            // Lifted without moving: a tap. (Held past LONG_PRESS_MS it is already HELD: down, up.)
            State.ONE -> out.click("left")
            State.MOVING -> flush(t.time, force = true)
            State.HELD -> {
                flush(t.time, force = true)
                out.click("left", "up")
            }
            State.SCROLLING -> flush(t.time, force = true)
            else -> Unit
        }
        clearPending()
        state = State.IDLE
    }

    private fun checkLongPress(now: Long) {
        if (state != State.ONE || now - downTime < LONG_PRESS_MS) return
        state = State.HELD
        clearPending()
        out.click("left", "down")
        out.longPressEngaged()
    }

    // ---- sums ----

    private fun addMove(dxPx: Float, dyPx: Float) {
        moveX += dxPx / density
        moveY += dyPx / density
    }

    /**
     * Natural scrolling (on by default, a phone-only setting): the page follows the fingers, so the finger
     * movement is negated. Off, the scroll follows the fingers' movement as it is (fingers down = scroll down).
     */
    var naturalScrolling = true

    private fun addScroll(dxPx: Float, dyPx: Float) {
        val sign = if (naturalScrolling) -1.0 else 1.0
        scrollX += sign * dxPx / density
        scrollY += sign * dyPx / density
    }

    private fun flush(now: Long, force: Boolean) {
        if (!force && now - lastFlush < FLUSH_MS) return
        var sent = false
        if (moveX != 0.0 || moveY != 0.0) {
            out.move(moveX, moveY)
            moveX = 0.0
            moveY = 0.0
            sent = true
        }
        if (scrollX != 0.0 || scrollY != 0.0) {
            out.scroll(scrollX, scrollY)
            scrollX = 0.0
            scrollY = 0.0
            sent = true
        }
        if (sent && !force) lastFlush = now
    }

    private fun clearPending() {
        moveX = 0.0
        moveY = 0.0
        scrollX = 0.0
        scrollY = 0.0
    }

    /** Midpoint of the two scrolling fingers, or null if one of them is missing from [t]. */
    private fun centroid(t: Touch): Pair<Float, Float>? {
        val a = t.pointers.firstOrNull { it.id == twoIds[0] } ?: return null
        val b = t.pointers.firstOrNull { it.id == twoIds[1] } ?: return null
        return Pair((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    }
}
