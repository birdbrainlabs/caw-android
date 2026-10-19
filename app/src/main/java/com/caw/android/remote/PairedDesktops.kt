package com.caw.android.remote

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The list of paired computers as [RemoteStore] keeps it: a JSON array in pairing order, each entry
 * `{"id", "name", "fingerprint", "host", "port"}`. Pure, so it can be unit tested.
 */
object PairedDesktops {

    fun encode(list: List<PairedDesktop>): String = JSONArray().apply {
        for (d in list) {
            put(JSONObject().put("id", d.id).put("name", d.name).put("fingerprint", d.fingerprintHex).put("host", d.host).put("port", d.port))
        }
    }.toString()

    /** Entries without an id, fingerprint or host are left out; nothing stored (or unreadable) is no computers. */
    fun decode(stored: String?): List<PairedDesktop> {
        if (stored == null) return emptyList()
        val a = try {
            JSONArray(stored)
        } catch (e: JSONException) {
            return emptyList()
        }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val fp = o.optString("fingerprint")
            val host = o.optString("host")
            if (id.isEmpty() || fp.isEmpty() || host.isEmpty()) return@mapNotNull null
            PairedDesktop(id, o.optString("name").ifEmpty { "computer" }, fp, host, o.optInt("port", PairTarget.DEFAULT_PORT))
        }
    }

    /** [list] with [d] added at the end, or in place of the entry with its id (pairing the same computer again). */
    fun with(list: List<PairedDesktop>, d: PairedDesktop): List<PairedDesktop> =
        if (list.any { it.id == d.id }) list.map { if (it.id == d.id) d else it } else list + d

    fun without(list: List<PairedDesktop>, id: String): List<PairedDesktop> = list.filter { it.id != id }

    /** What the Computer screen calls [d]: its name, with its address when another paired computer has the same name. */
    fun label(d: PairedDesktop, all: List<PairedDesktop>): String =
        if (all.any { it.id != d.id && it.name.equals(d.name, ignoreCase = true) }) "${d.name} (${d.host})" else d.name
}
