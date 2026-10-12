package com.caw.android.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionTest {

    private class FakeWire : RemoteSession.Wire {
        val lines = mutableListOf<String>()
        var closed: String? = null
        var failWrites = false

        override fun write(line: String): Boolean {
            if (failWrites) return false
            lines += line
            return true
        }

        override fun close(reason: String) {
            closed = reason
        }

        fun types() = lines.map { JSONObject(it).getString("type") }
        fun sent(type: String) = lines.map { JSONObject(it) }.filter { it.getString("type") == type }
        fun clear() = lines.clear()
    }

    private val events = mutableListOf<String>()
    private val listener = object : RemoteSession.Listener {
        override fun onReady(desktopName: String) {
            events += "ready $desktopName"
        }

        override fun onAcked(item: Outbox.Item) {
            events += "acked ${item.id}"
        }

        override fun onRejected(item: Outbox.Item?, reason: String) {
            events += "rejected ${item?.id} $reason"
        }

        override fun onQueueChanged(waiting: Int) {
            events += "waiting $waiting"
        }
    }

    private val session = RemoteSession("phone01", listener)

    private fun ready(wire: FakeWire, now: Long = 0) {
        session.attach(wire, now)
        session.onLine("""{"type":"hello_ok","desktop_name":"my-laptop"}""", now)
    }

    @Test
    fun attachSendsHelloFirst() {
        val w = FakeWire()
        session.attach(w, 0)
        assertEquals(listOf("""hello"""), w.types())
        assertEquals("phone01", JSONObject(w.lines[0]).getString("device_id"))
        assertEquals(RemoteSession.Phase.HELLO_SENT, session.phase)
    }

    @Test
    fun helloOkMakesItReady() {
        val w = FakeWire()
        ready(w)
        assertEquals(RemoteSession.Phase.READY, session.phase)
        assertEquals("my-laptop", session.desktopName)
        assertTrue("ready my-laptop" in events)
    }

    @Test
    fun idsIncrease() {
        val w = FakeWire()
        ready(w)
        session.sendText("One. ")
        session.sendKey("backspace")
        session.sendText("Two. ")
        session.sendKey("enter")
        val ids = w.lines.drop(1).map { JSONObject(it).getLong("id") }
        assertEquals(listOf(1L, 2L, 3L, 4L), ids)
        assertEquals(listOf("hello", "text", "key", "text", "key"), w.types())
        assertEquals("enter", w.sent("key")[1].getString("key"))
        assertEquals(1, w.sent("key")[1].getInt("count"))
    }

    @Test
    fun helloSaysV2() {
        val w = FakeWire()
        session.attach(w, 0)
        assertEquals(2, JSONObject(w.lines[0]).getInt("v"))
    }

    @Test
    fun touchpadMessagesAreNeverQueued() {
        // Not connected: dropped.
        assertFalse(session.sendUnqueued(Protocol.pointerMove(1.0, 2.0)))
        val w = FakeWire()
        session.attach(w, 0)
        // Connected but before hello_ok: dropped too.
        assertFalse(session.sendUnqueued(Protocol.click("left")))
        session.onLine("""{"type":"hello_ok","desktop_name":"my-laptop"}""", 1)
        assertEquals(listOf("hello"), w.types())
        assertTrue(session.sendUnqueued(Protocol.pointerMove(1.0, 2.0)))
        assertTrue(session.sendUnqueued(Protocol.scroll(0.0, 12.0)))
        assertTrue(session.sendUnqueued(Protocol.click("left", "down")))
        assertEquals(listOf("hello", "pointer_move", "scroll", "click"), w.types())
        assertEquals(0, session.outbox.size)
        // Nothing of them comes back on the next connection.
        session.detach()
        val w2 = FakeWire()
        ready(w2, 10)
        assertEquals(listOf("hello"), w2.types())
    }

    @Test
    fun pointerUnavailableIsAnErrorWithoutAnItem() {
        val w = FakeWire()
        ready(w)
        session.sendText("Hi. ")
        session.onLine("""{"type":"error","reason":"pointer_unavailable"}""", 5)
        assertTrue("rejected null pointer_unavailable" in events)
        assertEquals(1, session.outbox.size) // the text still waits for its own ack
    }

    @Test
    fun ackRemovesFromTheQueue() {
        val w = FakeWire()
        ready(w)
        session.sendText("Hi. ")
        assertEquals(1, session.outbox.size)
        session.onLine("""{"type":"ack","id":1}""", 10)
        assertEquals(0, session.outbox.size)
        assertTrue("acked 1" in events)
    }

    @Test
    fun errorRemovesAndReports() {
        val w = FakeWire()
        ready(w)
        session.sendText("Hi. ")
        session.onLine("""{"type":"error","id":1,"reason":"no_focus"}""", 10)
        assertEquals(0, session.outbox.size)
        assertTrue("rejected 1 no_focus" in events)
        session.onLine("""{"type":"error","reason":"unknown_type"}""", 10)
        assertTrue("rejected null unknown_type" in events)
    }

    @Test
    fun textWhileDisconnectedIsQueuedAndSentOnReconnect() {
        session.sendText("Waiting one. ")
        session.sendKey("enter")
        session.sendText("Waiting two. ")
        assertEquals(3, session.outbox.size)
        assertEquals("waiting 3", events.last())

        val w = FakeWire()
        session.attach(w, 100)
        // Nothing but hello until the desktop says hello_ok.
        assertEquals(listOf("hello"), w.types())
        session.onLine("""{"type":"hello_ok","desktop_name":"my-laptop"}""", 110)
        assertEquals(listOf("hello", "text", "key", "text"), w.types())
        assertEquals(listOf("Waiting one. ", "Waiting two. "), w.sent("text").map { it.getString("text") })
        assertEquals(listOf(1L, 2L, 3L), w.lines.drop(1).map { JSONObject(it).getLong("id") })
    }

    @Test
    fun unackedTextIsResentAfterTheConnectionDrops() {
        val w1 = FakeWire()
        ready(w1)
        session.sendText("Lost in flight. ")
        session.sendText("Acked. ")
        session.onLine("""{"type":"ack","id":2}""", 5)
        session.detach()
        assertEquals(RemoteSession.Phase.DOWN, session.phase)
        session.sendText("While away. ")

        val w2 = FakeWire()
        ready(w2, 1_000)
        assertEquals(listOf("Lost in flight. ", "While away. "), w2.sent("text").map { it.getString("text") })
        // Same ids as the first time, and still increasing on this connection.
        assertEquals(listOf(1L, 3L), w2.sent("text").map { it.getLong("id") })
    }

    @Test
    fun nothingIsSentTwiceOnOneConnection() {
        val w = FakeWire()
        ready(w)
        session.sendText("A. ")
        session.sendText("B. ")
        assertEquals(2, w.sent("text").size)
    }

    @Test
    fun failedWriteStaysQueued() {
        val w = FakeWire()
        ready(w)
        w.failWrites = true
        session.sendText("Kept. ")
        assertEquals(1, session.outbox.unsent().size)
    }

    @Test
    fun pingsEvery15Seconds() {
        val w = FakeWire()
        ready(w, 0)
        session.tick(14_999)
        assertFalse("ping" in w.types())
        session.onLine("""{"type":"pong"}""", 14_999)
        session.tick(15_000)
        assertEquals(1, w.sent("ping").size)
        session.onLine("""{"type":"pong"}""", 15_100)
        session.tick(29_999)
        assertEquals(1, w.sent("ping").size)
        session.tick(30_000)
        assertEquals(2, w.sent("ping").size)
    }

    @Test
    fun dropsAfter45SecondsOfSilence() {
        val w = FakeWire()
        ready(w, 0)
        session.tick(44_999)
        assertNull(w.closed)
        session.tick(45_000)
        assertTrue(w.closed!!.contains("45"))
    }

    @Test
    fun anyMessageCountsAsHearing() {
        val w = FakeWire()
        ready(w, 0)
        session.onLine("""{"type":"pong"}""", 40_000)
        session.tick(60_000)
        assertNull(w.closed)
        session.tick(85_000)
        assertTrue(w.closed != null)
    }

    @Test
    fun noHelloOkWithin10SecondsCloses() {
        val w = FakeWire()
        session.attach(w, 0)
        session.tick(9_999)
        assertNull(w.closed)
        session.tick(10_000)
        assertEquals("no hello_ok", w.closed)
    }

    @Test
    fun desktopPingGetsPong() {
        val w = FakeWire()
        ready(w)
        session.onLine("""{"type":"ping"}""", 1)
        assertEquals("pong", w.types().last())
    }

    @Test
    fun unknownTypesAreIgnored() {
        val w = FakeWire()
        ready(w)
        session.onLine("""{"type":"pointer_move","dx":1}""", 1)
        session.onLine("garbage", 1)
        assertEquals(RemoteSession.Phase.READY, session.phase)
        assertEquals(listOf("hello"), w.types())
    }

    @Test
    fun tickWithoutConnectionDoesNothing() {
        session.tick(1_000_000)
        assertEquals(RemoteSession.Phase.DOWN, session.phase)
    }

    // ---- outbox ----

    @Test
    fun outboxDropsOldestWhenFull() {
        val box = Outbox(capacity = 3)
        repeat(5) { box.add(Outbox.Payload.Text("t$it")) }
        assertEquals(3, box.size)
        assertEquals(2, box.dropped)
        assertEquals(listOf(3L, 4L, 5L), box.all().map { it.id })
    }

    // ---- reconnect ----

    @Test
    fun backoffDoublesUpTo30Seconds() {
        val p = ReconnectPolicy()
        val delays = (1..8).map { p.onFailure().delayMs }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun resetStartsOver() {
        val p = ReconnectPolicy()
        repeat(5) { p.onFailure() }
        p.reset()
        assertEquals(0, p.failures)
        assertEquals(1_000L, p.onFailure().delayMs)
    }

    @Test
    fun discoveryOnFirstFailureThenEveryFourth() {
        val p = ReconnectPolicy()
        val discover = (1..9).map { p.onFailure().discover }
        assertEquals(listOf(true, false, false, false, true, false, false, false, true), discover)
    }

    @Test
    fun manyFailuresDoNotOverflow() {
        val p = ReconnectPolicy()
        repeat(200) { p.onFailure() }
        assertEquals(30_000L, p.onFailure().delayMs)
    }
}
