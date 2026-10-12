package com.caw.android.touchpad

import com.caw.android.touchpad.TouchGestures.Action
import com.caw.android.touchpad.TouchGestures.Pointer
import com.caw.android.touchpad.TouchGestures.Touch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchGesturesTest {

    /** What the recogniser sent, as short strings: "move 4.0,-2.0", "scroll 0.0,8.0", "click left down". */
    private val sent = mutableListOf<String>()
    private var ticks = 0

    private val out = object : TouchGestures.Output {
        override fun move(dx: Double, dy: Double) {
            sent += "move ${r(dx)},${r(dy)}"
        }

        override fun scroll(dx: Double, dy: Double) {
            sent += "scroll ${r(dx)},${r(dy)}"
        }

        override fun click(button: String, action: String?) {
            sent += "click $button" + (action?.let { " $it" } ?: "")
        }

        override fun longPressEngaged() {
            ticks++
        }
    }

    private fun r(v: Double) = Math.round(v * 100) / 100.0

    /** Density 2.625 as on a 420 dpi phone: 2.625 px = 1 dp. */
    private val density = 2.625f
    private val g = TouchGestures(density, out)

    private fun one(action: Action, t: Long, x: Float, y: Float) = g.onTouch(Touch(action, t, listOf(Pointer(0, x, y))))

    private fun two(action: Action, t: Long, ax: Float, ay: Float, bx: Float, by: Float, changed: Int = 1) =
        g.onTouch(Touch(action, t, listOf(Pointer(0, ax, ay), Pointer(1, bx, by)), changed))

    private fun moves() = sent.filter { it.startsWith("move") }

    @Test
    fun tapIsALeftClick() {
        one(Action.DOWN, 0, 500f, 500f)
        one(Action.MOVE, 40, 502f, 501f) // inside the slop
        g.tick(50)
        one(Action.UP, 90, 502f, 501f)
        assertEquals(listOf("click left"), sent)
        assertFalse(g.active)
    }

    @Test
    fun twoFingerTapIsARightClick() {
        one(Action.DOWN, 0, 400f, 500f)
        two(Action.POINTER_DOWN, 20, 400f, 500f, 600f, 500f)
        two(Action.MOVE, 60, 401f, 500f, 601f, 501f)
        g.tick(70)
        two(Action.POINTER_UP, 120, 401f, 500f, 601f, 501f, changed = 1)
        one(Action.UP, 130, 401f, 500f)
        assertEquals(listOf("click right"), sent)
    }

    @Test
    fun aSlowTwoFingerTouchIsNotAClick() {
        one(Action.DOWN, 0, 400f, 500f)
        two(Action.POINTER_DOWN, 20, 400f, 500f, 600f, 500f)
        two(Action.POINTER_UP, 20 + TouchGestures.TWO_TAP_MS + 50, 400f, 500f, 600f, 500f)
        one(Action.UP, 500, 400f, 500f)
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun dragSendsTheMovementInDp() {
        one(Action.DOWN, 0, 100f, 100f)
        // 52.5 px right and 26.25 px up = 20 dp right, 10 dp up, across several events and ticks.
        one(Action.MOVE, 5, 126.25f, 100f) // 10 dp: past the slop (8 dp = 21 px), becomes a move
        g.tick(12)
        one(Action.MOVE, 15, 131.5f, 89.5f) // +2 dp, -4 dp
        g.tick(24)
        one(Action.MOVE, 30, 152.5f, 73.75f) // +8 dp, -6 dp, sent when the finger lifts
        one(Action.UP, 35, 152.5f, 73.75f)
        assertEquals(listOf("move 10.0,0.0", "move 2.0,-4.0", "move 8.0,-6.0"), sent)
        val sumX = moves().sumOf { it.removePrefix("move ").split(",")[0].toDouble() }
        val sumY = moves().sumOf { it.removePrefix("move ").split(",")[1].toDouble() }
        assertEquals(20.0, sumX, 0.02)
        assertEquals(-10.0, sumY, 0.02)
        assertFalse(sent.any { it.startsWith("click") })
    }

    @Test
    fun theLiftsPositionCounts() {
        one(Action.DOWN, 0, 100f, 100f)
        one(Action.MOVE, 10, 126.25f, 100f)
        g.tick(10)
        one(Action.UP, 20, 131.5f, 94.75f) // 2 dp right, 2 dp up since the last MOVE
        assertEquals(listOf("move 10.0,0.0", "move 2.0,-2.0"), sent)
    }

    @Test
    fun movesAreSummedAndSentAtMostEvery12Ms() {
        one(Action.DOWN, 0, 0f, 0f)
        one(Action.MOVE, 1, 30f, 0f) // past the slop
        g.tick(1) // first flush: 30 px
        // Ten events 1 ms apart, 2.625 px (1 dp) each, and a tick after every one.
        for (i in 1..10) {
            one(Action.MOVE, 1L + i, 30f + i * 2.625f, 0f)
            g.tick(1L + i)
        }
        // Nothing more until 12 ms after the first flush, then the ten moves as one.
        assertEquals(listOf("move 11.43,0.0"), sent)
        g.tick(13)
        assertEquals(listOf("move 11.43,0.0", "move 10.0,0.0"), sent)
        g.tick(20) // nothing new: nothing sent
        assertEquals(2, sent.size)
    }

    @Test
    fun twoFingerDragScrollsNaturally() {
        one(Action.DOWN, 0, 400f, 1000f)
        two(Action.POINTER_DOWN, 10, 400f, 1000f, 600f, 1000f)
        // Both fingers move UP the screen by 105 px (40 dp): the page scrolls DOWN, dy positive.
        two(Action.MOVE, 30, 400f, 947.5f, 600f, 947.5f)
        g.tick(30)
        two(Action.MOVE, 50, 400f, 895f, 600f, 895f)
        two(Action.POINTER_UP, 60, 400f, 895f, 600f, 895f)
        one(Action.UP, 70, 400f, 895f)
        assertEquals(listOf("scroll 0.0,20.0", "scroll 0.0,20.0"), sent)

        sent.clear()
        // Fingers move right by 52.5 px (20 dp): content follows, so dx is negative.
        one(Action.DOWN, 100, 400f, 1000f)
        two(Action.POINTER_DOWN, 110, 400f, 1000f, 600f, 1000f)
        two(Action.MOVE, 130, 452.5f, 1000f, 652.5f, 1000f)
        two(Action.POINTER_UP, 140, 452.5f, 1000f, 652.5f, 1000f)
        assertEquals(listOf("scroll -20.0,0.0"), sent)
        assertFalse(sent.any { it.startsWith("click") })
    }

    @Test
    fun longPressThenDragIsDownMovesUp() {
        one(Action.DOWN, 0, 300f, 300f)
        one(Action.MOVE, 200, 302f, 301f) // still: jitter inside the slop
        g.tick(390)
        assertEquals(emptyList<String>(), sent)
        g.tick(TouchGestures.LONG_PRESS_MS + 2)
        assertEquals(listOf("click left down"), sent)
        assertEquals(1, ticks)
        // The jitter before it engaged is not sent; movement after it is.
        one(Action.MOVE, 420, 323f, 301f)
        g.tick(420)
        one(Action.MOVE, 440, 344f, 301f)
        one(Action.UP, 445, 344f, 301f)
        assertEquals(listOf("click left down", "move 8.0,0.0", "move 8.0,0.0", "click left up"), sent)
    }

    @Test
    fun aStillLongPressIsDownThenUp() {
        one(Action.DOWN, 0, 300f, 300f)
        g.tick(500)
        one(Action.UP, 700, 300f, 300f)
        assertEquals(listOf("click left down", "click left up"), sent)
    }

    @Test
    fun movingBeforeTheLongPressIsJustAMove() {
        one(Action.DOWN, 0, 300f, 300f)
        one(Action.MOVE, 100, 340f, 300f)
        g.tick(100)
        g.tick(600) // too late for a long press: already moving
        one(Action.UP, 650, 340f, 300f)
        assertEquals(listOf("move 15.24,0.0"), sent)
    }

    @Test
    fun cancelReleasesAHeldButton() {
        one(Action.DOWN, 0, 300f, 300f)
        g.tick(450)
        assertTrue(g.active)
        g.cancel() // a hold to talk started
        assertEquals(listOf("click left down", "click left up"), sent)
        assertFalse(g.active)
    }

    @Test
    fun threeFingersDoNothing() {
        one(Action.DOWN, 0, 100f, 100f)
        two(Action.POINTER_DOWN, 10, 100f, 100f, 200f, 100f)
        g.onTouch(Touch(Action.POINTER_DOWN, 20, listOf(Pointer(0, 100f, 100f), Pointer(1, 200f, 100f), Pointer(2, 300f, 100f)), 2))
        g.onTouch(Touch(Action.MOVE, 40, listOf(Pointer(0, 100f, 200f), Pointer(1, 200f, 200f), Pointer(2, 300f, 200f)), 0))
        g.tick(40)
        two(Action.POINTER_UP, 60, 100f, 200f, 200f, 200f)
        one(Action.UP, 70, 100f, 200f)
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun naturalScrollingOffSendsTheFingerMovementAsItIs() {
        g.naturalScrolling = false
        one(Action.DOWN, 0, 400f, 1000f)
        two(Action.POINTER_DOWN, 10, 400f, 1000f, 600f, 1000f)
        // Both fingers move UP the screen by 105 px (40 dp): with natural scrolling off the page scrolls UP.
        two(Action.MOVE, 30, 452.5f, 895f, 652.5f, 895f)
        two(Action.POINTER_UP, 40, 452.5f, 895f, 652.5f, 895f)
        assertEquals(listOf("scroll 20.0,-40.0"), sent)

        // Back on: the same movement is negated.
        sent.clear()
        g.naturalScrolling = true
        one(Action.DOWN, 100, 400f, 1000f)
        two(Action.POINTER_DOWN, 110, 400f, 1000f, 600f, 1000f)
        two(Action.MOVE, 130, 452.5f, 895f, 652.5f, 895f)
        two(Action.POINTER_UP, 140, 452.5f, 895f, 652.5f, 895f)
        assertEquals(listOf("scroll -20.0,40.0"), sent)
    }
}
