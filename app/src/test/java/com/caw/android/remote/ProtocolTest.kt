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
        JSONObject(Protocol.hello("ab12", "9f2c41d07ab3e655")).let {
            assertEquals("hello", it.getString("type"))
            assertEquals(4, it.getInt("v")) // v4: shortcuts
            assertEquals("ab12", it.getString("device_id"))
            assertEquals("9f2c41d07ab3e655", it.getString("session"))
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
            Protocol.Incoming.PairOk("d1ca", "omarchy", "cHJvb2Y="),
            Protocol.parse("""{"type":"pair_ok","desktop_id":"d1ca","desktop_name":"omarchy","proof":"cHJvb2Y="}"""),
        )
        assertEquals(Protocol.Incoming.PairError("bad_code"), Protocol.parse("""{"type":"pair_error","reason":"bad_code"}"""))
        assertEquals(Protocol.Incoming.HelloOk("omarchy"), Protocol.parse("""{"type":"hello_ok","desktop_name":"omarchy"}"""))
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

    // ---- v3: settings ----

    @Test
    fun settingsGetAndSet() {
        JSONObject(Protocol.settingsGet(7)).let {
            assertEquals("settings_get", it.getString("type"))
            assertEquals(7L, it.getLong("id"))
            assertEquals(2, it.length())
        }
        // Only the fields that changed.
        JSONObject(Protocol.settingsSet(8, SettingsChange(pointerSpeed = 2.0))).let {
            assertEquals("settings_set", it.getString("type"))
            assertEquals(8L, it.getLong("id"))
            assertEquals(2.0, it.getDouble("pointer_speed"), 0.0)
            assertFalse(it.has("pointer_accel"))
            assertFalse(it.has("word_fixes"))
        }
        // word_fixes is the whole map; an empty map clears the list (it is still sent).
        JSONObject(Protocol.settingsSet(9, SettingsChange(wordFixes = mapOf("car app" to "Caw app", "call app" to "Caw app")))).let {
            val m = it.getJSONObject("word_fixes")
            assertEquals(2, m.length())
            assertEquals("Caw app", m.getString("car app"))
            assertFalse(it.has("pointer_speed"))
        }
        assertEquals(0, JSONObject(Protocol.settingsSet(10, SettingsChange(wordFixes = emptyMap()))).getJSONObject("word_fixes").length())
    }

    @Test
    fun parsesSettings() {
        val m = Protocol.parse("""{"type":"settings","id":7,"desktop_name":"omarchy","desktop_version":"0.1.0",""" +
            """"word_fixes":{"car app":"Caw app"},"pointer_speed":1.5,"pointer_accel":0.05}""")
        assertTrue(m is Protocol.Incoming.Settings)
        m as Protocol.Incoming.Settings
        assertEquals(7L, m.id)
        assertEquals("omarchy", m.settings.desktopName)
        assertEquals("0.1.0", m.settings.desktopVersion)
        assertEquals(mapOf("car app" to "Caw app"), m.settings.wordFixes)
        assertEquals(1.5, m.settings.pointerSpeed, 0.0)
        assertEquals(0.05, m.settings.pointerAccel, 0.0)
    }

    @Test
    fun settingsWithFieldsMissingUseTheDefaults() {
        val m = Protocol.parse("""{"type":"settings","id":3,"desktop_name":"omarchy"}""") as Protocol.Incoming.Settings
        assertEquals(null, m.settings.desktopVersion)
        assertTrue(m.settings.wordFixes.isEmpty())
        assertEquals(SettingsLimits.SPEED_DEFAULT, m.settings.pointerSpeed, 0.0)
        assertEquals(SettingsLimits.ACCEL_DEFAULT, m.settings.pointerAccel, 0.0)
    }

    @Test
    fun settingsLimits() {
        assertEquals(null, SettingsLimits.problem(SettingsChange(pointerSpeed = 0.1)))
        assertEquals(null, SettingsLimits.problem(SettingsChange(pointerSpeed = 10.0, pointerAccel = 1.0)))
        assertTrue(SettingsLimits.problem(SettingsChange(pointerSpeed = 0.05)) != null)
        assertTrue(SettingsLimits.problem(SettingsChange(pointerSpeed = 10.1)) != null)
        assertTrue(SettingsLimits.problem(SettingsChange(pointerAccel = -0.01)) != null)
        assertTrue(SettingsLimits.problem(SettingsChange(pointerAccel = 1.01)) != null)
        val five = (1..500).associate { "w$it" to "x" }
        assertEquals(null, SettingsLimits.problem(SettingsChange(wordFixes = five)))
        assertTrue(SettingsLimits.problem(SettingsChange(wordFixes = five + ("one more" to "x"))) != null)
        assertTrue(SettingsLimits.problem(SettingsChange(wordFixes = mapOf("a".repeat(101) to "x"))) != null)
        assertTrue(SettingsLimits.problem(SettingsChange(wordFixes = mapOf("a" to "x".repeat(101)))) != null)
        // One typed fix: both sides needed; the heard phrase is stored as the desktop does.
        assertEquals(null, SettingsLimits.fixProblem("Car App", "Caw app"))
        assertTrue(SettingsLimits.fixProblem("  ", "Caw") != null)
        assertTrue(SettingsLimits.fixProblem("car", " ") != null)
        assertEquals("car app", SettingsLimits.phrase("  Car   APP "))
    }

    // ---- v4: shortcuts ----

    @Test
    fun keyCarriesModsOnlyWhenThereAreSome() {
        JSONObject(Protocol.key(5, "enter", 1, listOf("super"))).let {
            assertEquals("enter", it.getString("key"))
            assertEquals(listOf("super"), List(it.getJSONArray("mods").length()) { i -> it.getJSONArray("mods").getString(i) })
            assertEquals(1, it.getInt("count"))
        }
        assertFalse(JSONObject(Protocol.key(6, "backspace", 1)).has("mods"))
        assertFalse(JSONObject(Protocol.key(7, "page_up", 1, emptyList())).has("mods"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownModIsRefused() {
        Protocol.key(1, "t", 1, listOf("hyper"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun modTwiceIsRefused() {
        Protocol.key(1, "t", 1, listOf("ctrl", "ctrl"))
    }

    @Test
    fun extendedKeysAreKnown() {
        for (k in listOf("a", "z", "0", "9", "f1", "f12", "space", "page_up", "page_down", "insert", "minus", "equal",
            "comma", "period", "slash", "semicolon", "apostrophe", "bracket_left", "bracket_right", "backslash", "grave")) {
            assertTrue(k, k in Protocol.KEYS)
        }
    }

    @Test
    fun settingsCarryShortcutsInOrder() {
        val m = Protocol.parse("""{"type":"settings","id":4,"desktop_name":"omarchy","shortcuts":[""" +
            """{"name":"Terminal","key":"enter","mods":["super"]},{"name":"Bad","key":"f13","mods":[]},""" +
            """{"name":"Refresh","key":"f5"}]}""") as Protocol.Incoming.Settings
        // The one this phone cannot send is left out; no mods reads as none.
        assertEquals(listOf(Shortcut("Terminal", "enter", listOf("super")), Shortcut("Refresh", "f5")), m.settings.shortcuts)
        // An older desktop sends no shortcuts at all: null, not empty.
        assertEquals(null, (Protocol.parse("""{"type":"settings","id":5,"desktop_name":"omarchy"}""") as Protocol.Incoming.Settings).settings.shortcuts)
        assertEquals(emptyList<Shortcut>(), (Protocol.parse("""{"type":"settings","id":6,"desktop_name":"omarchy","shortcuts":[]}""") as Protocol.Incoming.Settings).settings.shortcuts)
    }

    @Test
    fun settingsSetSendsTheWholeShortcutList() {
        val list = listOf(Shortcut("Terminal", "enter", listOf("super")), Shortcut("New tab", "t", listOf("ctrl", "shift")))
        JSONObject(Protocol.settingsSet(11, SettingsChange(shortcuts = list))).let {
            val a = it.getJSONArray("shortcuts")
            assertEquals(2, a.length())
            assertEquals("Terminal", a.getJSONObject(0).getString("name"))
            assertEquals("t", a.getJSONObject(1).getString("key"))
            assertEquals(2, a.getJSONObject(1).getJSONArray("mods").length())
            assertFalse(it.has("word_fixes"))
        }
        assertEquals(0, JSONObject(Protocol.settingsSet(12, SettingsChange(shortcuts = emptyList()))).getJSONArray("shortcuts").length())
        assertFalse(JSONObject(Protocol.settingsSet(13, SettingsChange(pointerSpeed = 2.0))).has("shortcuts"))
    }

    @Test
    fun shortcutLimits() {
        val one = Shortcut("Terminal", "enter", listOf("super"))
        assertEquals(null, SettingsLimits.problem(SettingsChange(shortcuts = List(48) { one })))
        assertTrue(SettingsLimits.problem(SettingsChange(shortcuts = List(49) { one })) != null)
        assertTrue(SettingsLimits.problem(SettingsChange(shortcuts = listOf(one.copy(name = "   ")))) != null)
        assertEquals(null, SettingsLimits.problem(SettingsChange(shortcuts = listOf(one.copy(name = " " + "x".repeat(30) + " ")))))
        assertTrue(SettingsLimits.problem(SettingsChange(shortcuts = listOf(one.copy(name = "x".repeat(31))))) != null)
        assertTrue(SettingsLimits.problem(SettingsChange(shortcuts = listOf(one.copy(key = "f13")))) != null)
        assertEquals(null, SettingsLimits.shortcutProblem(" Terminal "))
        assertTrue(SettingsLimits.shortcutProblem("") != null)
    }
}
