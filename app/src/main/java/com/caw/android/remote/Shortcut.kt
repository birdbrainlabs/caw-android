package com.caw.android.remote

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * A shortcut button (protocol v4): pressing it sends `key` [key] with [mods] held, once. The list
 * lives on the computer, in its `settings` (`shortcuts`), in the order it is shown.
 */
data class Shortcut(val name: String, val key: String, val mods: List<String> = emptyList()) {

    /** The desktop would accept it: a known key, known modifiers, none twice. The name is not checked. */
    val valid: Boolean get() = key in Protocol.KEYS && mods.all { it in Protocol.MODS } && mods.distinct().size == mods.size

    fun toJson(): JSONObject = JSONObject().put("name", name).put("key", key).put("mods", JSONArray(mods))

    companion object {
        /** One entry of a `shortcuts` list; null if it is not one this phone could send. */
        fun fromJson(o: JSONObject): Shortcut? {
            val mods = o.optJSONArray("mods")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            return Shortcut(o.optString("name"), o.optString("key"), mods).takeIf { it.valid }
        }

        /** A `shortcuts` list; entries this phone cannot send are left out. */
        fun listFromJson(a: JSONArray): List<Shortcut> =
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { fromJson(it) } }

        fun listToJson(shortcuts: List<Shortcut>): JSONArray = JSONArray().apply { shortcuts.forEach { put(it.toJson()) } }
    }
}

/** How keys and combos read on screen: "Super+Enter", "Ctrl+Shift+T", "F5", "Page Up", "-". */
object ShortcutKeys {

    private val MOD_LABELS = mapOf("super" to "Super", "ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Shift")

    private val NAMED = linkedMapOf(
        "enter" to "Enter", "space" to "Space", "tab" to "Tab", "escape" to "Escape",
        "backspace" to "Backspace", "delete" to "Delete",
        "left" to "Left", "right" to "Right", "up" to "Up", "down" to "Down",
        "home" to "Home", "end" to "End", "page_up" to "Page Up", "page_down" to "Page Down", "insert" to "Insert",
    )

    private val PUNCTUATION = linkedMapOf(
        "minus" to "-", "equal" to "=", "comma" to ",", "period" to ".", "slash" to "/", "semicolon" to ";",
        "apostrophe" to "'", "bracket_left" to "[", "bracket_right" to "]", "backslash" to "\\", "grave" to "`",
    )

    /** The key picker's order: the common keys, letters, digits, F-keys, then punctuation. */
    val PICKER: List<String> = buildList {
        addAll(NAMED.keys.take(6))
        addAll(('a'..'z').map { it.toString() })
        addAll(('0'..'9').map { it.toString() })
        addAll((1..12).map { "f$it" })
        addAll(NAMED.keys.drop(6))
        addAll(PUNCTUATION.keys)
    }

    fun label(key: String): String = NAMED[key] ?: PUNCTUATION[key] ?: key.uppercase()

    /** The modifiers in the order the desktop presses them (super, ctrl, alt, shift), then the key. */
    fun combo(key: String, mods: List<String>): String =
        (Protocol.MODS.filter { it in mods }.map { MOD_LABELS.getValue(it) } + label(key)).joinToString("+")

    fun combo(shortcut: Shortcut): String = combo(shortcut.key, shortcut.mods)
}

/**
 * The paired computer's shortcuts as kept in the phone's prefs, so the buttons show at once next
 * time, before Caw has connected: a JSON list, or [UNSUPPORTED] for a computer whose Caw Desktop
 * has no shortcuts (older than v4). Nothing stored reads as unsupported too: no buttons.
 */
object ShortcutCache {
    const val UNSUPPORTED = "unsupported"

    fun encode(shortcuts: List<Shortcut>?): String = shortcuts?.let { Shortcut.listToJson(it).toString() } ?: UNSUPPORTED

    fun decode(stored: String?): List<Shortcut>? {
        if (stored == null || stored == UNSUPPORTED) return null
        return try {
            Shortcut.listFromJson(JSONArray(stored))
        } catch (e: JSONException) {
            null
        }
    }
}
