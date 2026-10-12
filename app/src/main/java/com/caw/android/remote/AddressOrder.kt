package com.caw.android.remote

/**
 * Which addresses to try, in what order, when connecting to the paired computer
 * (see RemoteLink). Plain strings, no java.net, so it is unit tested.
 *
 * After a short Wi-Fi drop NsdManager sometimes resolves the computer to its IPv6 link-local
 * address only (`fe80::…%wlan0`). Caw used to store that and retry only it. Now every attempt
 * tries the stored address and every address the last lookup found, IPv4 first, then host names,
 * then IPv6, moving on to the next one when one fails; and a lookup that found only IPv6 never
 * replaces a stored IPv4 address.
 */
object AddressOrder {

    enum class Family { V4, NAME, V6 }

    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    /** An IPv4 literal, an IPv6 literal (with or without `[]` and a `%scope`), or anything else (a name). */
    fun family(host: String): Family {
        val h = host.trim()
        return when {
            IPV4.matches(h) -> Family.V4
            h.contains(':') -> Family.V6
            else -> Family.NAME
        }
    }

    /** `[fe80::1%wlan0]` -> `fe80::1%wlan0`; other hosts are only trimmed. */
    fun bare(host: String): String {
        val h = host.trim()
        return if (h.startsWith("[") && h.endsWith("]")) h.substring(1, h.length - 1) else h
    }

    /**
     * Every address to try, each once: IPv4 first, then names, then IPv6. Within a family the
     * [stored] address comes before the [resolved] ones (it is the one that last worked), and the
     * resolved ones keep the order the lookup gave them.
     */
    fun candidates(stored: String, resolved: List<String>): List<String> {
        val all = (listOf(stored) + resolved).map(::bare).filter { it.isNotEmpty() }
        val seen = HashSet<String>()
        val unique = all.filter { seen.add(it.lowercase()) }
        return Family.values().flatMap { f -> unique.filter { family(it) == f } }
    }

    /**
     * The address to keep as the computer's stored address after a lookup found [resolved]:
     * a stored name stays (it was typed on purpose); else the first IPv4 address found; else the
     * stored address if it is IPv4 (an IPv6-only answer must not replace a working IPv4 address);
     * else the lookup's first address.
     */
    fun toStore(stored: String, resolved: List<String>): String {
        val s = bare(stored)
        if (s.isNotEmpty() && family(s) == Family.NAME) return s
        val found = resolved.map(::bare).filter { it.isNotEmpty() }
        found.firstOrNull { family(it) == Family.V4 }?.let { return it }
        if (s.isNotEmpty() && family(s) == Family.V4) return s
        return found.firstOrNull() ?: s
    }

    /**
     * After a connection to [connected] worked: the address to store instead of [stored], or null
     * to keep it. An IPv4 address that worked replaces a stored IPv6 or different IPv4 address; a
     * stored name (typed on purpose) is kept, and a name or IPv6 address that worked never replaces anything.
     */
    fun afterSuccess(stored: String, connected: String): String? {
        val c = bare(connected)
        if (family(c) != Family.V4 || family(stored) == Family.NAME) return null
        return if (c.equals(bare(stored), ignoreCase = true)) null else c
    }
}
