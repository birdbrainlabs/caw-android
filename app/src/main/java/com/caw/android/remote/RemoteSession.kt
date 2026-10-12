package com.caw.android.remote

import kotlin.math.min

/**
 * What the phone has asked the computer to do and the computer has not yet acknowledged.
 * Ids keep increasing for the life of the process. An item stays here until its `ack` (or
 * `error`) arrives, so text dictated while the computer is unreachable, or sent just before the
 * connection broke, goes out again on the next connection instead of being lost.
 */
class Outbox(private val capacity: Int = 100) {

    sealed class Payload {
        data class Text(val text: String) : Payload()
        data class Key(val key: String, val count: Int) : Payload()
    }

    class Item(val id: Long, val payload: Payload) {
        /** Written on the current connection (waiting for its ack). */
        var sent = false
            internal set

        fun line(): String = when (payload) {
            is Payload.Text -> Protocol.text(id, payload.text)
            is Payload.Key -> Protocol.key(id, payload.key, payload.count)
        }
    }

    private val items = ArrayList<Item>()
    private var nextId = 1L

    /** Items thrown away because the queue was full (oldest first). */
    var dropped = 0
        private set

    val size: Int get() = items.size

    /** The next id. Settings requests (v3) take theirs from here too, so no two messages share an id. */
    fun takeId(): Long = nextId++

    fun add(payload: Payload): Item {
        val item = Item(takeId(), payload)
        items += item
        while (items.size > capacity) {
            items.removeAt(0)
            dropped++
        }
        return item
    }

    /** In the order they were added. */
    fun unsent(): List<Item> = items.filter { !it.sent }

    fun all(): List<Item> = items.toList()

    fun markSent(item: Item) {
        item.sent = true
    }

    /** The connection went away: everything not acknowledged goes out again next time. */
    fun markAllUnsent() = items.forEach { it.sent = false }

    fun remove(id: Long): Item? {
        val i = items.indexOfFirst { it.id == id }
        return if (i < 0) null else items.removeAt(i)
    }

    fun clear() = items.clear()
}

/**
 * When to try connecting again. Delays double from [firstMs] up to [maxMs]; a connection that
 * reached `hello_ok` resets them. Discovery (NsdManager) runs after the first failure in a row and
 * then every [discoverEvery] failures, in case the computer's address changed.
 */
class ReconnectPolicy(
    private val firstMs: Long = 1_000,
    private val maxMs: Long = 30_000,
    private val discoverEvery: Int = 4,
) {
    data class Decision(val delayMs: Long, val discover: Boolean)

    /** Failed attempts since the last good connection. */
    var failures = 0
        private set

    fun onFailure(): Decision {
        failures++
        val shift = min(failures - 1, 20)
        val delay = min(maxMs, firstMs shl shift)
        val discover = failures == 1 || (failures - 1) % discoverEvery == 0
        return Decision(delay, discover)
    }

    /** `hello_ok` arrived, or something (network back, Caw shown) says try straight away. */
    fun reset() {
        failures = 0
    }
}

/**
 * The protocol logic of one paired computer, without sockets or threads, so it can be unit
 * tested. [RemoteLink] owns the socket, calls everything here from one thread and passes the time.
 *
 * Per connection: [attach] sends `hello`; `hello_ok` makes it [Phase.READY] and flushes the
 * outbox; pings every 15 s; 45 s without hearing anything closes it ([Wire.close]).
 *
 * Settings requests (v3) are written only while ready and never queued: each waits for its
 * answer (`settings`, `ack` or `error` with its id) and fails when the connection goes or after
 * [REQUEST_TIMEOUT_MS].
 */
class RemoteSession(private val deviceId: String, private val listener: Listener, capacity: Int = 100) {

    interface Wire {
        /** One JSON line (no newline). Returns false if the write failed; the wire then closes itself. */
        fun write(line: String): Boolean

        fun close(reason: String)
    }

    interface Listener {
        fun onReady(desktopName: String)
        fun onAcked(item: Outbox.Item)
        /** The desktop answered `error`; [item] is null if it named no id or an unknown one. */
        fun onRejected(item: Outbox.Item?, reason: String)
        fun onQueueChanged(waiting: Int)

        /** v3: the `settings` answer to the request [id]. */
        fun onSettings(id: Long, settings: DesktopSettings) {}

        /** v3: the computer acknowledged the `settings_set` [id]. */
        fun onSettingsSaved(id: Long) {}

        /** v3: the settings request [id] got no answer, or an `error`. */
        fun onRequestFailed(id: Long, failure: RequestFailure) {}
    }

    sealed class RequestFailure {
        /** The computer answered `error` with this reason. */
        data class Refused(val reason: String) : RequestFailure()
        object ConnectionLost : RequestFailure()
        object TimedOut : RequestFailure()
    }

    enum class Phase { DOWN, HELLO_SENT, READY }

    companion object {
        const val PING_EVERY_MS = 15_000L
        const val SILENCE_MS = 45_000L
        const val HELLO_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 10_000L
    }

    val outbox = Outbox(capacity)

    var phase = Phase.DOWN
        private set

    var desktopName: String? = null
        private set

    private var wire: Wire? = null
    private var attachedAt = 0L
    private var lastHeard = 0L
    private var lastPing = 0L

    /** Settings requests waiting for their answer: id -> when it was sent. */
    private val requests = LinkedHashMap<Long, Long>()

    /** Settings requests still waiting for an answer. */
    val pendingRequests: Int get() = requests.size

    /** A new connection (already past TLS and the fingerprint check). */
    fun attach(wire: Wire, now: Long) {
        this.wire = wire
        phase = Phase.HELLO_SENT
        attachedAt = now
        lastHeard = now
        lastPing = now
        outbox.markAllUnsent()
        wire.write(Protocol.hello(deviceId))
    }

    /** The connection is gone. Unacknowledged items stay queued. */
    fun detach() {
        wire = null
        phase = Phase.DOWN
        outbox.markAllUnsent()
        failRequests(RequestFailure.ConnectionLost)
    }

    /**
     * v3: asks for the computer's settings. Returns the request's id, or null (nothing sent) when
     * the computer is not ready: settings requests are never queued.
     */
    fun requestSettings(now: Long): Long? = request(now) { Protocol.settingsGet(it) }

    /** v3: changes the computer's settings; null (nothing sent) when it is not ready. */
    fun changeSettings(change: SettingsChange, now: Long): Long? = request(now) { Protocol.settingsSet(it, change) }

    private fun request(now: Long, line: (Long) -> String): Long? {
        val w = wire ?: return null
        if (phase != Phase.READY) return null
        val id = outbox.takeId()
        requests[id] = now
        if (!w.write(line(id))) {
            requests.remove(id)
            return null
        }
        return id
    }

    private fun failRequests(failure: RequestFailure) {
        if (requests.isEmpty()) return
        val ids = requests.keys.toList()
        requests.clear()
        for (id in ids) listener.onRequestFailed(id, failure)
    }

    fun sendText(text: String): Outbox.Item = queue(Outbox.Payload.Text(text))

    fun sendKey(key: String, count: Int = 1): Outbox.Item = queue(Outbox.Payload.Key(key, count))

    /**
     * Touchpad messages (v2): written now if the desktop is ready, otherwise dropped. Never queued
     * and never sent again: a mouse move that arrives late is worse than one that is lost.
     */
    fun sendUnqueued(line: String): Boolean {
        val w = wire ?: return false
        if (phase != Phase.READY) return false
        return w.write(line)
    }

    private fun queue(payload: Outbox.Payload): Outbox.Item {
        val item = outbox.add(payload)
        flush()
        listener.onQueueChanged(outbox.size)
        return item
    }

    /** Writes every unsent item, oldest first, if the desktop is ready. */
    private fun flush() {
        val w = wire ?: return
        if (phase != Phase.READY) return
        for (item in outbox.unsent()) {
            if (!w.write(item.line())) return
            outbox.markSent(item)
        }
    }

    fun onLine(line: String, now: Long) {
        lastHeard = now
        when (val m = Protocol.parse(line)) {
            is Protocol.Incoming.HelloOk -> {
                phase = Phase.READY
                desktopName = m.desktopName
                listener.onReady(m.desktopName)
                flush()
            }
            is Protocol.Incoming.Ack -> {
                val item = outbox.remove(m.id)
                if (item != null) {
                    listener.onAcked(item)
                    listener.onQueueChanged(outbox.size)
                } else if (requests.remove(m.id) != null) {
                    listener.onSettingsSaved(m.id)
                }
            }
            is Protocol.Incoming.Error -> {
                if (m.id != null && requests.remove(m.id) != null) {
                    listener.onRequestFailed(m.id, RequestFailure.Refused(m.reason))
                    return
                }
                val item = m.id?.let { outbox.remove(it) }
                listener.onRejected(item, m.reason)
                if (item != null) listener.onQueueChanged(outbox.size)
            }
            is Protocol.Incoming.Settings -> {
                // An answer without an id, or to a request that already timed out, is ignored.
                if (m.id != null && requests.remove(m.id) != null) listener.onSettings(m.id, m.settings)
            }
            Protocol.Incoming.Ping -> wire?.write(Protocol.pong())
            Protocol.Incoming.Pong -> Unit
            // pair_ok / pair_error belong to pairing; anything else is from a newer desktop.
            else -> Unit
        }
    }

    /** Call about once a second while attached. */
    fun tick(now: Long) {
        val w = wire ?: return
        if (now - lastHeard >= SILENCE_MS) {
            w.close("nothing heard for ${SILENCE_MS / 1000} s")
            return
        }
        if (phase == Phase.HELLO_SENT && now - attachedAt >= HELLO_TIMEOUT_MS) {
            w.close("no hello_ok")
            return
        }
        val late = requests.filterValues { now - it >= REQUEST_TIMEOUT_MS }.keys
        for (id in late) {
            requests.remove(id)
            listener.onRequestFailed(id, RequestFailure.TimedOut)
        }
        if (phase == Phase.READY && now - lastPing >= PING_EVERY_MS) {
            lastPing = now
            w.write(Protocol.ping())
        }
    }
}
