package com.caw.android.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    // ---- framing ----

    @Test
    fun wholeLines() {
        val f = LineFramer()
        assertEquals(listOf("""{"a":1}""", """{"b":2}"""), f.feed("{\"a\":1}\n{\"b\":2}\n".toByteArray()))
        assertEquals(0, f.pending)
    }

    @Test
    fun partialLineWaitsForItsNewline() {
        val f = LineFramer()
        assertEquals(emptyList<String>(), f.feed("""{"type":"ac""".toByteArray()))
        assertEquals(listOf("""{"type":"ack","id":1}"""), f.feed("k\",\"id\":1}\n{\"ty".toByteArray()))
        assertEquals(4, f.pending)
        assertEquals(listOf("""{"type":"pong"}"""), f.feed("pe\":\"pong\"}\n".toByteArray()))
    }

    @Test
    fun utf8CharacterSplitAcrossReads() {
        val bytes = "{\"text\":\"café ✓\"}\n".toByteArray(Charsets.UTF_8)
        val f = LineFramer()
        val out = mutableListOf<String>()
        // One byte at a time cuts both multi-byte characters.
        for (b in bytes) out += f.feed(byteArrayOf(b))
        assertEquals(listOf("{\"text\":\"café ✓\"}"), out)
    }

    @Test
    fun blankLinesAndCarriageReturnsAreDropped() {
        val f = LineFramer()
        assertEquals(listOf("{}"), f.feed("\n\r\n{}\r\n  \n".toByteArray()))
    }

    @Test
    fun offsetAndLengthAreRespected() {
        val buf = "xx{\"a\":1}\nyy".toByteArray()
        assertEquals(listOf("{\"a\":1}"), LineFramer().feed(buf, 2, 8))
    }

    @Test
    fun overlongLineIsDroppedAndTheNextOneSurvives() {
        val f = LineFramer(maxLineBytes = 16)
        assertEquals(emptyList<String>(), f.feed("0123456789".toByteArray()))
        assertEquals(emptyList<String>(), f.feed("0123456789".toByteArray()))
        assertEquals(listOf("{\"ok\":1}"), f.feed("tail\n{\"ok\":1}\n".toByteArray()))
    }

    @Test
    fun encodeEndsInOneNewline() {
        val line = String(LineFramer.encode(Protocol.ping()), Charsets.UTF_8)
        assertTrue(line.endsWith("}\n"))
        assertEquals(1, line.count { it == '\n' })
    }

    @Test
    fun textWithNewlinesStaysOnOneLine() {
        val line = Protocol.text(3, "one\ntwo\n")
        assertEquals(false, line.contains('\n'))
        assertEquals("one\ntwo\n", JSONObject(line).getString("text"))
    }

    // ---- messages ----

    @Test
    fun outgoingMessages() {
        JSONObject(Protocol.pairRequest("ab12", "Test Phone", "cHJvb2Y=")).let {
            assertEquals("pair_request", it.getString("type"))
            assertEquals(1, it.getInt("v"))
            assertEquals("ab12", it.getString("device_id"))
            assertEquals("Test Phone", it.getString("device_name"))
            assertEquals("cHJvb2Y=", it.getString("proof"))
        }
        JSONObject(Protocol.hello("ab12")).let {
            assertEquals("hello", it.getString("type"))
            assertEquals(2, it.getInt("v")) // v2: touchpad
            assertEquals("ab12", it.getString("device_id"))
        }
        JSONObject(Protocol.text(1, "Hello world. ")).let {
            assertEquals("text", it.getString("type"))
            assertEquals(1L, it.getLong("id"))
            assertEquals("Hello world. ", it.getString("text"))
        }
        JSONObject(Protocol.key(2, "backspace", 1)).let {
            assertEquals("key", it.getString("type"))
            assertEquals(2L, it.getLong("id"))
            assertEquals("backspace", it.getString("key"))
            assertEquals(1, it.getInt("count"))
        }
        assertEquals("ping", JSONObject(Protocol.ping()).getString("type"))
        assertEquals("pong", JSONObject(Protocol.pong()).getString("type"))
    }

    @Test
    fun touchpadMessages() {
        assertSameJson("""{"type":"pointer_move","dx":3.5,"dy":-1}""", Protocol.pointerMove(3.5, -1.0))
        JSONObject(Protocol.pointerMove(1.23456, -0.004)).let {
            assertEquals("pointer_move", it.getString("type"))
            assertEquals(1.23, it.getDouble("dx"), 1e-9)
            assertEquals(0.0, it.getDouble("dy"), 1e-9)
            assertFalse(it.has("id"))
        }
        assertSameJson("""{"type":"click","button":"left"}""", Protocol.click("left"))
        assertSameJson("""{"type":"click","button":"right"}""", Protocol.click("right"))
        assertSameJson("""{"type":"click","button":"left","action":"down"}""", Protocol.click("left", "down"))
        assertSameJson("""{"type":"click","button":"left","action":"up"}""", Protocol.click("left", "up"))
        JSONObject(Protocol.click("middle", id = 9)).let {
            assertEquals(9L, it.getLong("id"))
            assertEquals("middle", it.getString("button"))
            assertFalse(it.has("action"))
        }
        assertSameJson("""{"type":"scroll","dx":0,"dy":12}""", Protocol.scroll(0.0, 12.0))
        assertEquals(-2.5, JSONObject(Protocol.scroll(-2.5, 0.0)).getDouble("dx"), 1e-9)
    }

    /** Same fields and values (the JVM test org.json does not keep key order; Android's does). */
    private fun assertSameJson(expected: String, actual: String) {
        fun fields(json: String): Map<String, String> = JSONObject(json).let { o -> o.keys().asSequence().associateWith { o.get(it).toString() } }
        assertEquals(fields(expected), fields(actual))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownButtonIsRefused() {
        Protocol.click("back")
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownClickActionIsRefused() {
        Protocol.click("left", "hold")
    }

    @Test
    fun pairRequestStaysV1() {
        assertEquals(1, JSONObject(Protocol.pairRequest("ab12", "Test Phone", "cA==")).getInt("v"))
    }

    @Test
    fun pointerUnavailableParsesAsAnErrorWithoutId() {
        assertEquals(
            Protocol.Incoming.Error(null, Protocol.POINTER_UNAVAILABLE),
            Protocol.parse("""{"type":"error","reason":"pointer_unavailable"}"""),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownKeyIsRefused() {
        Protocol.key(1, "f13", 1)
    }

    @Test
    fun incomingMessages() {
        assertEquals(
            Protocol.Incoming.PairOk("d1ca", "my-laptop", "cHJvb2Y="),
            Protocol.parse("""{"type":"pair_ok","desktop_id":"d1ca","desktop_name":"my-laptop","proof":"cHJvb2Y="}"""),
        )
        assertEquals(Protocol.Incoming.PairError("bad_code"), Protocol.parse("""{"type":"pair_error","reason":"bad_code"}"""))
        assertEquals(Protocol.Incoming.HelloOk("my-laptop"), Protocol.parse("""{"type":"hello_ok","desktop_name":"my-laptop"}"""))
        assertEquals(Protocol.Incoming.Ack(7), Protocol.parse("""{"type":"ack","id":7}"""))
        assertEquals(Protocol.Incoming.Error(7, "no_focus"), Protocol.parse("""{"type":"error","id":7,"reason":"no_focus"}"""))
        assertEquals(Protocol.Incoming.Error(null, "unknown_type"), Protocol.parse("""{"type":"error","reason":"unknown_type"}"""))
        assertEquals(Protocol.Incoming.Ping, Protocol.parse("""{"type":"ping"}"""))
        assertEquals(Protocol.Incoming.Pong, Protocol.parse("""{"type":"pong"}"""))
    }

    @Test
    fun unknownOrBrokenLinesAreOther() {
        assertEquals(Protocol.Incoming.Other("pointer_move", """{"type":"pointer_move","dx":1}"""), Protocol.parse("""{"type":"pointer_move","dx":1}"""))
        assertEquals(Protocol.Incoming.Other(null, "not json"), Protocol.parse("not json"))
        assertEquals(Protocol.Incoming.Other(null, "[1,2]"), Protocol.parse("[1,2]"))
    }
}
