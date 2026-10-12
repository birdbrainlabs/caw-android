package com.caw.android.remote

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Caw Remote Protocol messages (docs/remote-protocol.md). Each is one JSON object per line. */
object Protocol {
    /** `hello` says v3 (v2 touchpad messages, v3 settings); the desktop accepts v1, v2 and v3. */
    const val VERSION = 3

    /** `pair_request` stays at v1: pairing did not change in v2 or v3. */
    const val PAIR_VERSION = 1

    /** The desktop's `error` reason (v2, once per connection, no id) when it cannot move the mouse. */
    const val POINTER_UNAVAILABLE = "pointer_unavailable"

    /** Mouse buttons a `click` may name (v2). */
    val BUTTONS = setOf("left", "right", "middle")

    /** The special keys a `key` message may name. */
    val KEYS = setOf("backspace", "delete", "enter", "tab", "escape", "left", "right", "up", "down", "home", "end")

    fun pairRequest(deviceId: String, deviceName: String, proof: String): String = JSONObject()
        .put("type", "pair_request").put("v", PAIR_VERSION).put("device_id", deviceId)
        .put("device_name", deviceName).put("proof", proof).toString()

    fun hello(deviceId: String): String =
        JSONObject().put("type", "hello").put("v", VERSION).put("device_id", deviceId).toString()

    fun text(id: Long, text: String): String =
        JSONObject().put("type", "text").put("id", id).put("text", text).toString()

    fun key(id: Long, key: String, count: Int): String {
        require(key in KEYS) { "unknown key $key" }
        return JSONObject().put("type", "key").put("id", id).put("key", key).put("count", count).toString()
    }

    // ---- v2: touchpad. No id, no ack, never queued (a late mouse move is worse than a lost one). ----

    /** Move the mouse by the finger's movement in dp (raw: the desktop applies acceleration). */
    fun pointerMove(dx: Double, dy: Double): String =
        JSONObject().put("type", "pointer_move").put("dx", round2(dx)).put("dy", round2(dy)).toString()

    /**
     * A click of [button]; with [action] "down" or "up" a press or release (a drag). [id] asks for
     * an `ack`; the touchpad sends none.
     */
    fun click(button: String, action: String? = null, id: Long? = null): String {
        require(button in BUTTONS) { "unknown button $button" }
        require(action == null || action == "down" || action == "up") { "unknown action $action" }
        val o = JSONObject().put("type", "click")
        if (id != null) o.put("id", id)
        o.put("button", button)
        if (action != null) o.put("action", action)
        return o.toString()
    }

    /** Scroll in dp: positive [dy] scrolls down, positive [dx] right. */
    fun scroll(dx: Double, dy: Double): String =
        JSONObject().put("type", "scroll").put("dx", round2(dx)).put("dy", round2(dy)).toString()

    /** Two decimals are plenty for dp and keep the lines short. */
    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0

    // ---- v3: settings. Carry an id like text/key, but are never queued for resend. ----

    fun settingsGet(id: Long): String = JSONObject().put("type", "settings_get").put("id", id).toString()

    /** Only the fields of [change] that are set; `word_fixes` is the WHOLE list (it replaces the computer's). */
    fun settingsSet(id: Long, change: SettingsChange): String {
        val o = JSONObject().put("type", "settings_set").put("id", id)
        change.wordFixes?.let { fixes ->
            val m = JSONObject()
            for ((heard, write) in fixes) m.put(heard, write)
            o.put("word_fixes", m)
        }
        change.pointerSpeed?.let { o.put("pointer_speed", it) }
        change.pointerAccel?.let { o.put("pointer_accel", it) }
        return o.toString()
    }

    fun ping(): String = JSONObject().put("type", "ping").toString()

    fun pong(): String = JSONObject().put("type", "pong").toString()

    /** A message from the desktop. */
    sealed class Incoming {
        data class PairOk(val desktopId: String, val desktopName: String, val proof: String) : Incoming()
        data class PairError(val reason: String) : Incoming()
        data class HelloOk(val desktopName: String) : Incoming()
        data class Ack(val id: Long) : Incoming()
        data class Error(val id: Long?, val reason: String) : Incoming()
        /** v3: the answer to `settings_get`. */
        data class Settings(val id: Long?, val settings: DesktopSettings) : Incoming()
        object Ping : Incoming()
        object Pong : Incoming()
        /** A type this phone does not know (a newer desktop), or a line that is not a JSON object. */
        data class Other(val type: String?, val line: String) : Incoming()
    }

    fun parse(line: String): Incoming {
        val o = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return Incoming.Other(null, line)
        }
        val type = o.optString("type", "")
        return when (type) {
            "pair_ok" -> Incoming.PairOk(o.optString("desktop_id"), o.optString("desktop_name"), o.optString("proof"))
            "pair_error" -> Incoming.PairError(o.optString("reason", "unknown"))
            "hello_ok" -> Incoming.HelloOk(o.optString("desktop_name"))
            "ack" -> if (o.has("id")) Incoming.Ack(o.optLong("id")) else Incoming.Other(type, line)
            "error" -> Incoming.Error(if (o.has("id") && !o.isNull("id")) o.optLong("id") else null, o.optString("reason", "unknown"))
            "settings" -> Incoming.Settings(if (o.has("id") && !o.isNull("id")) o.optLong("id") else null, settings(o))
            "ping" -> Incoming.Ping
            "pong" -> Incoming.Pong
            else -> Incoming.Other(type.ifEmpty { null }, line)
        }
    }

    /** A `settings` reply; missing speed / acceleration read as the desktop's defaults. */
    private fun settings(o: JSONObject): DesktopSettings {
        val fixes = LinkedHashMap<String, String>()
        o.optJSONObject("word_fixes")?.let { m ->
            for (heard in m.keys()) fixes[heard] = m.optString(heard)
        }
        return DesktopSettings(
            desktopName = o.optString("desktop_name"),
            desktopVersion = if (o.has("desktop_version") && !o.isNull("desktop_version")) o.optString("desktop_version") else null,
            wordFixes = fixes,
            pointerSpeed = o.optDouble("pointer_speed", SettingsLimits.SPEED_DEFAULT),
            pointerAccel = o.optDouble("pointer_accel", SettingsLimits.ACCEL_DEFAULT),
        )
    }
}

/**
 * Newline-delimited JSON framing: bytes in, whole lines out. Bytes are split on '\n' before they
 * are decoded, so a UTF-8 character cut between two reads is never broken. Blank lines are skipped,
 * a trailing '\r' is dropped. A line longer than [maxLineBytes] is thrown away (up to its newline).
 */
class LineFramer(private val maxLineBytes: Int = 1 shl 20) {
    private val partial = ByteArrayOutputStream()
    private var overflowing = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<String> {
        val lines = mutableListOf<String>()
        var start = offset
        val end = offset + length
        for (i in offset until end) {
            if (bytes[i] != '\n'.code.toByte()) continue
            append(bytes, start, i - start)
            if (!overflowing) {
                val line = partial.toString("UTF-8").removeSuffix("\r")
                if (line.isNotBlank()) lines += line
            }
            partial.reset()
            overflowing = false
            start = i + 1
        }
        append(bytes, start, end - start)
        return lines
    }

    /** Bytes waiting for their newline. */
    val pending: Int get() = partial.size()

    private fun append(bytes: ByteArray, from: Int, count: Int) {
        if (count <= 0 || overflowing) return
        if (partial.size() + count > maxLineBytes) {
            overflowing = true
            partial.reset()
            return
        }
        partial.write(bytes, from, count)
    }

    companion object {
        /** One line on the wire: the JSON and a newline, UTF-8. */
        fun encode(json: String): ByteArray = (json + "\n").toByteArray(Charsets.UTF_8)
    }
}
