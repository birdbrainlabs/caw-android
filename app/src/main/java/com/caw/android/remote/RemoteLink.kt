package com.caw.android.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * "Send to computer": the connection to the paired Caw Desktop (docs/remote-protocol.md).
 * One per process, shared by the input method and [com.caw.android.ComputerActivity].
 *
 * Threads: everything that touches the socket or [RemoteSession] runs on the "CawRemote" thread;
 * each connection has its own reader thread that only hands lines over. Observers are called on
 * the main thread. While the switch is on and a computer is paired, it stays connected: pings
 * every 15 s, reconnects with backoff, and looks the computer up by mDNS when its address fails.
 * The touchpad and settings screens keep it connected the same way while they are open, switch or not.
 *
 * Each attempt tries every address it knows ([AddressOrder]: the stored one and those the last
 * mDNS lookup found, IPv4 first), moving on when one fails; only when all fail does it wait
 * out the backoff.
 */
object RemoteLink {
    private const val TAG = "CawRemote"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val HANDSHAKE_TIMEOUT_MS = 8_000
    private const val PAIR_ANSWER_TIMEOUT_MS = 15_000
    /** A screen keeps the connection this long after it closes, so turning the phone does not drop it. */
    private const val SCREEN_LINGER_MS = 10_000L

    /** Screens that keep Caw connected while they are open ([holdOpen]). */
    const val SCREEN_TOUCHPAD = "touchpad"
    const val SCREEN_SETTINGS = "settings"

    /** Touchpad messages are logged (debug) as one summary line per this long, not one per message. */
    private const val POINTER_LOG_MS = 250L

    enum class Connection { OFF, CONNECTING, CONNECTED, WAITING }

    data class State(
        val desktop: PairedDesktop?,
        /** The "Send to computer" switch (only ever true while paired). */
        val enabled: Boolean,
        val connection: Connection,
        /** Messages waiting for the computer (queued while away, or sent and not yet acknowledged). */
        val waiting: Int,
        /** Why the last connection attempt failed, in words; null once connected. */
        val problem: String?,
        /** The computer said `pointer_unavailable` on this connection: it cannot move its mouse. */
        val pointerUnavailable: Boolean = false,
    ) {
        /** Finished text goes to the computer instead of the phone's field. */
        val sending: Boolean get() = enabled && desktop != null
        val connected: Boolean get() = connection == Connection.CONNECTED
    }

    /** The answer to [getSettings] / [setSettings], on the main thread. */
    sealed class SettingsReply {
        data class Loaded(val settings: DesktopSettings) : SettingsReply()
        object Saved : SettingsReply()
        /** The computer answered `error` with [reason]. */
        data class Refused(val reason: String) : SettingsReply()
        /** Not sent: the computer is not connected (settings requests are never queued). */
        object NotConnected : SettingsReply()
        /** Sent, but the connection went away or nothing came back in 10 s. */
        object NoAnswer : SettingsReply()
    }

    interface Observer {
        fun onState(state: State)
        /** The computer acknowledged a dictated text. */
        fun onTextDelivered() {}
        /** The computer answered `error` for something the phone sent. */
        fun onRejected(reason: String) {}
    }

    sealed class PairResult {
        data class Paired(val name: String) : PairResult()
        data class Failed(val reason: String) : PairResult()
    }

    private val main = Handler(Looper.getMainLooper())
    private val observers = CopyOnWriteArrayList<Observer>()
    private lateinit var app: Context
    private lateinit var store: RemoteStore
    private lateinit var control: ScheduledExecutorService
    private var initialized = false

    @Volatile
    var state = State(null, false, Connection.OFF, 0, null)
        private set

    // ---- CawRemote thread only ----
    private var desktop: PairedDesktop? = null
    private var enabled = false
    private var connection = Connection.OFF
    private var problem: String? = null
    private var pointerUnavailable = false
    private var session: RemoteSession? = null
    private var conn: Conn? = null
    private val policy = ReconnectPolicy()
    private var retry: ScheduledFuture<*>? = null
    /** Screens that are open (or closed less than [SCREEN_LINGER_MS] ago) and keep the link up. */
    private val heldBy = HashSet<String>()
    private val lingers = HashMap<String, ScheduledFuture<*>>()
    /** Addresses the last mDNS lookup found for the paired computer, tried after the stored one. */
    private var resolvedHosts: List<String> = emptyList()
    /** A background lookup for an IPv4 address already ran for this computer id (once per process). */
    private var ipv4LookupFor: String? = null
    /** Settings requests waiting for their answer: id -> callback (called on the main thread). */
    private val settingsCallbacks = HashMap<Long, (SettingsReply) -> Unit>()
    private val movesLog = DeltaLog("pointer_move")
    private val scrollsLog = DeltaLog("scroll")
    private var pointerLogFlush: ScheduledFuture<*>? = null

    /** Stay connected: Send to computer is on, or the touchpad or settings screen is open. */
    private val wanted: Boolean get() = enabled || heldBy.isNotEmpty()

    fun init(context: Context) {
        synchronized(this) {
            if (initialized) return
            initialized = true
            app = context.applicationContext
            store = RemoteStore(app)
            control = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "CawRemote").apply { isDaemon = true } }
        }
        val d = store.desktop()
        state = State(d, store.enabled && d != null, Connection.OFF, 0, null)
        control.execute {
            desktop = d
            enabled = store.enabled && d != null
            ensure()
        }
        // Wi-Fi back: try at once instead of waiting out the backoff.
        try {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = kick()
            })
        } catch (t: Throwable) {
            Log.w(TAG, "no network callback", t)
        }
    }

    /** Main thread. Calls [observer] at once with the current state. */
    fun addObserver(observer: Observer) {
        observers.addIfAbsent(observer)
        observer.onState(state)
    }

    fun removeObserver(observer: Observer) {
        observers.remove(observer)
    }

    fun setEnabled(on: Boolean) = control.execute {
        enabled = on && desktop != null
        store.enabled = enabled
        policy.reset()
        cancelRetry()
        ensure()
        publish()
    }

    fun forget() = control.execute {
        store.forget()
        desktop = null
        resolvedHosts = emptyList()
        enabled = false
        session?.outbox?.clear()
        closeConn("forgotten")
        cancelRetry()
        problem = null
        ensure()
        publish()
    }

    /** Try to connect now (Caw was shown, the network came back). */
    fun kick() {
        if (!initialized) return
        control.execute {
            if (conn != null || !wanted) return@execute
            policy.reset()
            cancelRetry()
            ensure()
        }
    }

    /** Queues [text] for the computer; it goes as soon as the computer is connected. */
    fun sendText(text: String) = control.execute {
        withSession { it.sendText(text) }
    }

    fun sendKey(key: String, count: Int = 1) = control.execute {
        withSession { it.sendKey(key, count) }
    }

    // ---- screens that keep the link up ----

    /**
     * The [screen] ([SCREEN_TOUCHPAD], [SCREEN_SETTINGS]) opened or closed. While one is open Caw
     * stays connected to the paired computer even with Send to computer off; after it closes the
     * link lingers 10 s (rotation).
     */
    fun holdOpen(screen: String, open: Boolean) {
        if (!initialized) return
        control.execute {
            lingers.remove(screen)?.cancel(false)
            if (open) {
                heldBy += screen
                if (conn == null) {
                    policy.reset()
                    cancelRetry()
                    ensure()
                }
            } else {
                lingers[screen] = control.schedule({
                    lingers.remove(screen)
                    heldBy -= screen
                    ensure()
                }, SCREEN_LINGER_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    // ---- settings (v3) ----

    /** Asks the computer for its settings. [done] gets [SettingsReply.NotConnected] at once when it is not connected. */
    fun getSettings(done: (SettingsReply) -> Unit) = control.execute {
        val id = if (conn != null) session?.requestSettings(now()) else null
        track(id, "settings_get", done)
    }

    /** Changes the computer's settings ([change]'s word fixes replace the whole list). */
    fun setSettings(change: SettingsChange, done: (SettingsReply) -> Unit) = control.execute {
        val id = if (conn != null) session?.changeSettings(change, now()) else null
        val what = buildList {
            change.wordFixes?.let { add("word_fixes: ${it.size}") }
            change.pointerSpeed?.let { add("pointer_speed: $it") }
            change.pointerAccel?.let { add("pointer_accel: $it") }
        }.joinToString(", ")
        track(id, "settings_set ($what)", done)
    }

    private fun track(id: Long?, what: String, done: (SettingsReply) -> Unit) {
        if (id == null) {
            Log.i(TAG, "dropped $what (not connected)")
            main.post { done(SettingsReply.NotConnected) }
            return
        }
        Log.i(TAG, "sent $what id $id")
        settingsCallbacks[id] = done
    }

    private fun answer(id: Long, reply: SettingsReply) {
        val done = settingsCallbacks.remove(id) ?: return
        main.post { done(reply) }
    }

    /** Moves the mouse by [dx], [dy] dp. Dropped when not connected. */
    fun sendMove(dx: Double, dy: Double) = control.execute {
        movesLog.add(dx, dy, writeUnqueued(Protocol.pointerMove(dx, dy)))
        schedulePointerLog()
    }

    /** Scrolls by [dx], [dy] dp (positive dy = down). Dropped when not connected. */
    fun sendScroll(dx: Double, dy: Double) = control.execute {
        scrollsLog.add(dx, dy, writeUnqueued(Protocol.scroll(dx, dy)))
        schedulePointerLog()
    }

    /** A click, or with [action] "down" / "up" a press or release. Dropped when not connected. */
    fun sendClick(button: String, action: String? = null) = control.execute {
        logPointerSummary()
        val sent = writeUnqueued(Protocol.click(button, action))
        Log.d(TAG, (if (sent) "sent" else "dropped (not connected)") + " click $button" + (action?.let { " $it" } ?: ""))
    }

    private fun writeUnqueued(line: String): Boolean = conn != null && session?.sendUnqueued(line) == true

    private fun schedulePointerLog() {
        if (pointerLogFlush != null) return
        pointerLogFlush = control.schedule({ logPointerSummary() }, POINTER_LOG_MS, TimeUnit.MILLISECONDS)
    }

    private fun logPointerSummary() {
        pointerLogFlush?.cancel(false)
        pointerLogFlush = null
        movesLog.take()?.let { Log.d(TAG, it) }
        scrollsLog.take()?.let { Log.d(TAG, it) }
    }

    /** Sums of touchpad messages for the debug log: "sent 21 pointer_move, sum dx=31.2 dy=-4.5". */
    private class DeltaLog(private val type: String) {
        private var sent = 0
        private var dropped = 0
        private var dx = 0.0
        private var dy = 0.0

        fun add(x: Double, y: Double, ok: Boolean) {
            if (ok) {
                sent++
                dx += x
                dy += y
            } else {
                dropped++
            }
        }

        fun take(): String? {
            if (sent == 0 && dropped == 0) return null
            val line = buildString {
                if (sent > 0) append(String.format(Locale.US, "sent %d %s, sum dx=%.1f dy=%.1f", sent, type, dx, dy))
                if (dropped > 0) append(if (sent > 0) "; " else "").append("dropped $dropped $type (not connected)")
            }
            sent = 0
            dropped = 0
            dx = 0.0
            dy = 0.0
            return line
        }
    }

    /** Pairs with the computer at [host]:[port] using [code]; [done] is called on the main thread. */
    fun pair(host: String, port: Int, code: String, nameHint: String?, done: (PairResult) -> Unit) {
        val bad = PairingCode.problem(code)
        if (bad != null) {
            done(PairResult.Failed(bad))
            return
        }
        control.execute {
            val result = try {
                pairNow(host.trim(), port, PairingCode.normalize(code), nameHint)
            } catch (t: Throwable) {
                Log.w(TAG, "pairing failed", t)
                PairResult.Failed(describe(t, host, port))
            }
            main.post { done(result) }
            publish()
        }
    }

    // ---- CawRemote thread ----

    private fun withSession(block: (RemoteSession) -> Unit) {
        val s = try {
            session()
        } catch (t: Throwable) {
            Log.w(TAG, "no identity", t)
            problem = "Could not make this phone's key: ${t.message}"
            publish()
            return
        }
        block(s)
        publish()
    }

    private fun session(): RemoteSession = session ?: RemoteSession(RemoteIdentity.get().deviceId, sessionListener).also { session = it }

    private val sessionListener = object : RemoteSession.Listener {
        override fun onReady(desktopName: String) {
            policy.reset()
            problem = null
            pointerUnavailable = false
            connection = Connection.CONNECTED
            val d = desktop
            if (d != null && desktopName.isNotBlank() && desktopName != d.name) {
                desktop = d.copy(name = desktopName).also { store.saveDesktop(it) }
            }
            Log.i(TAG, "connected to $desktopName")
            desktop?.let { findIpv4Later(it) }
            if (!wanted) {
                // The switch went off while connecting: hello_ok proved the link; nothing to keep open.
                closeConn("Send to computer is off")
                connection = Connection.OFF
            }
            publish()
        }

        override fun onAcked(item: Outbox.Item) {
            Log.i(TAG, "ack ${item.id}")
            if (item.payload is Outbox.Payload.Text) main.post { observers.forEach { it.onTextDelivered() } }
        }

        override fun onRejected(item: Outbox.Item?, reason: String) {
            if (item == null && reason == Protocol.POINTER_UNAVAILABLE) {
                // Once per connection, for the touchpad's messages: shown on the touchpad screen only.
                Log.w(TAG, "the computer cannot move its mouse ($reason)")
                pointerUnavailable = true
                publish()
                return
            }
            Log.w(TAG, "computer error for ${item?.id}: $reason")
            main.post { observers.forEach { it.onRejected(reason) } }
        }

        override fun onQueueChanged(waiting: Int) = Unit

        override fun onSettings(id: Long, settings: DesktopSettings) {
            // The word fixes themselves are not logged.
            Log.i(TAG, "settings $id: ${settings.desktopName} ${settings.desktopVersion}, ${settings.wordFixes.size} word fixes, " +
                "pointer_speed ${settings.pointerSpeed}, pointer_accel ${settings.pointerAccel}")
            answer(id, SettingsReply.Loaded(settings))
        }

        override fun onSettingsSaved(id: Long) {
            Log.i(TAG, "ack $id (settings saved)")
            answer(id, SettingsReply.Saved)
        }

        override fun onRequestFailed(id: Long, failure: RemoteSession.RequestFailure) {
            Log.w(TAG, "settings request $id failed: $failure")
            answer(id, if (failure is RemoteSession.RequestFailure.Refused) SettingsReply.Refused(failure.reason) else SettingsReply.NoAnswer)
        }
    }

    private fun ensure() {
        val d = desktop
        if (!wanted || d == null) {
            closeConn("off")
            cancelRetry()
            connection = Connection.OFF
            publish()
            return
        }
        if (conn != null || retry != null) return
        connection = Connection.CONNECTING
        publish()
        // Every known address, IPv4 first; the next one when one fails.
        val hosts = AddressOrder.candidates(d.host, resolvedHosts)
        val failures = ArrayList<Pair<String, Throwable>>()
        for (host in hosts) {
            val socket = try {
                open(host, d.port, PinnedTrust(d.fingerprintHex))
            } catch (t: Throwable) {
                Log.i(TAG, "connect $host port ${d.port} failed: ${describe(t, host, d.port)}")
                failures += host to t
                continue
            }
            AddressOrder.afterSuccess(d.host, host)?.let { better ->
                Log.i(TAG, "storing $better for ${d.name} (was ${d.host})")
                desktop = d.copy(host = better).also { store.saveDesktop(it) }
            }
            start(socket, LineFramer())
            return
        }
        connectFailed(d, failures)
    }

    /** Every address in [failures] failed (in the order tried). */
    private fun connectFailed(d: PairedDesktop, failures: List<Pair<String, Throwable>>) {
        // A certificate mismatch says the most; otherwise the preferred address's reason.
        val (host, t) = failures.firstOrNull { (_, e) -> isCertificateProblem(e) } ?: failures.firstOrNull()
            ?: (d.host to UnknownHostException(d.host))
        problem = describe(t, host, d.port) + if (failures.size > 1) " (tried ${failures.size} addresses)" else ""
        val decision = policy.onFailure()
        if (decision.discover) {
            val found = DesktopFinder(app).find(d.id)
            if (found != null && desktop?.id == d.id) {
                resolvedHosts = found.hosts
                val keep = AddressOrder.toStore(d.host, found.hosts)
                val tried = failures.map { it.first }.toSet()
                val news = AddressOrder.candidates(keep, found.hosts).filter { it !in tried }
                if (keep != d.host || found.port != d.port) {
                    desktop = d.copy(host = keep, port = found.port).also { store.saveDesktop(it) }
                }
                Log.i(TAG, "found ${d.name} at ${found.hosts} port ${found.port}; stored ${desktop?.host}")
                if (news.isNotEmpty() || found.port != d.port) {
                    scheduleRetry(0)
                    return
                }
            }
        }
        scheduleRetry(decision.delayMs)
    }

    /**
     * Connected over IPv6 with no IPv4 address stored (an address saved before the Oct 11 fix, or a
     * lookup that only found link-local): look the computer up once in the background so the next
     * reconnect can try IPv4 first. Off the CawRemote thread, so sending is not held up.
     */
    private fun findIpv4Later(d: PairedDesktop) {
        if (AddressOrder.family(d.host) != AddressOrder.Family.V6 || ipv4LookupFor == d.id) return
        ipv4LookupFor = d.id
        Thread({
            val found = DesktopFinder(app).find(d.id)
            control.execute {
                val now = desktop
                if (found == null || now?.id != d.id) return@execute
                resolvedHosts = found.hosts
                val keep = AddressOrder.toStore(now.host, found.hosts)
                Log.i(TAG, "background lookup: ${d.name} at ${found.hosts}; stored $keep")
                if (keep != now.host) desktop = now.copy(host = keep).also { store.saveDesktop(it) }
            }
        }, "CawRemoteFind").apply { isDaemon = true }.start()
    }

    private fun isCertificateProblem(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is CertificateException) return true
            cause = cause.cause
        }
        return false
    }

    private fun scheduleRetry(delayMs: Long) {
        cancelRetry()
        connection = Connection.WAITING
        retry = control.schedule({
            retry = null
            ensure()
        }, delayMs, TimeUnit.MILLISECONDS)
        publish()
    }

    private fun cancelRetry() {
        retry?.cancel(false)
        retry = null
    }

    private fun start(socket: SSLSocket, framer: LineFramer) {
        val c = Conn(socket, framer)
        conn = c
        c.ticker = control.scheduleWithFixedDelay({ if (conn === c) session?.tick(now()) }, 1, 1, TimeUnit.SECONDS)
        Thread({ c.readLoop() }, "CawRemoteRead").apply { isDaemon = true }.start()
        session().attach(c, now())
    }

    private fun closeConn(reason: String) {
        val c = conn ?: return
        conn = null
        c.shut()
        session?.detach()
        Log.i(TAG, "closed: $reason")
    }

    /** The connection [c] ended (read error, EOF, silence, failed write). */
    private fun dropped(c: Conn, reason: String) {
        if (conn !== c) return
        val wasReady = session?.phase == RemoteSession.Phase.READY
        closeConn(reason)
        val name = desktop?.name ?: "the computer"
        problem = if (wasReady) "Lost $name ($reason)" else "$name closed the connection before it was ready ($reason). If this keeps happening, pair again."
        if (wanted) scheduleRetry(policy.onFailure().delayMs) else {
            connection = Connection.OFF
            publish()
        }
    }

    private fun pairNow(host: String, port: Int, code: String, nameHint: String?): PairResult {
        closeConn("pairing")
        cancelRetry()
        val identity = RemoteIdentity.get()
        val socket = open(host, port, TrustAnyForPairing)
        var handedOver = false
        try {
            socket.soTimeout = PAIR_ANSWER_TIMEOUT_MS
            val desktopFp = PairProof.fingerprint(socket.session.peerCertificates[0].encoded)
            val phoneFp = identity.fingerprint
            val out = socket.outputStream
            out.write(LineFramer.encode(Protocol.pairRequest(identity.deviceId, deviceName(), PairProof.phone(code, desktopFp, phoneFp))))
            out.flush()
            val framer = LineFramer()
            val line = readLine(socket.inputStream, framer)
                ?: return PairResult.Failed("The computer closed the connection without answering.")
            return when (val m = Protocol.parse(line)) {
                is Protocol.Incoming.PairOk -> {
                    if (!PairProof.desktopMatches(m.proof, code, desktopFp, phoneFp)) {
                        return PairResult.Failed("The computer's answer did not match the code, so Caw did not trust it.")
                    }
                    val id = PairProof.shortId(desktopFp)
                    if (m.desktopId.isNotEmpty() && !m.desktopId.equals(id, ignoreCase = true)) {
                        return PairResult.Failed("The computer's id did not match its certificate.")
                    }
                    val name = m.desktopName.ifBlank { nameHint?.takeIf { it.isNotBlank() } ?: host }
                    val d = PairedDesktop(id, name, PairProof.hex(desktopFp), host, port)
                    if (desktop?.id != id) session?.outbox?.clear()
                    resolvedHosts = emptyList()
                    desktop = d
                    store.saveDesktop(d)
                    // Pairing means "send to this computer": the switch turns on (it can be turned off after).
                    enabled = true
                    store.enabled = true
                    problem = null
                    policy.reset()
                    Log.i(TAG, "paired with $name ($id) at $host:$port")
                    // Same connection carries on with hello.
                    socket.soTimeout = 0
                    connection = Connection.CONNECTING
                    start(socket, framer)
                    handedOver = true
                    PairResult.Paired(name)
                }
                is Protocol.Incoming.PairError -> PairResult.Failed(pairErrorText(m.reason))
                else -> PairResult.Failed("The computer answered something unexpected: $line")
            }
        } finally {
            if (!handedOver) {
                try {
                    socket.close()
                } catch (e: IOException) {
                }
                ensure()
            }
        }
    }

    private fun readLine(input: InputStream, framer: LineFramer): String? {
        val buf = ByteArray(4096)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return null
            val lines = framer.feed(buf, 0, n)
            if (lines.isNotEmpty()) {
                // Pairing expects exactly one answer before hello; anything after it is not ours.
                return lines.first()
            }
        }
    }

    private fun open(host: String, port: Int, trust: X509TrustManager): SSLSocket {
        val identity = RemoteIdentity.get()
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(identity.keyManager()), arrayOf(trust), null)
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(address(host), port), CONNECT_TIMEOUT_MS)
            raw.tcpNoDelay = true
            raw.keepAlive = true
            val s = ctx.socketFactory.createSocket(raw, AddressOrder.bare(host), port, true) as SSLSocket
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.useClientMode = true
            s.soTimeout = HANDSHAKE_TIMEOUT_MS
            s.startHandshake()
            s.soTimeout = 0
            return s
        } catch (t: Throwable) {
            try {
                raw.close()
            } catch (e: IOException) {
            }
            throw t
        }
    }

    /**
     * [host] as an address. An IPv6 link-local address keeps its scope (`fe80::…%wlan0` or `%23`);
     * if the platform does not take the interface name, it is looked up by hand.
     */
    private fun address(host: String): InetAddress {
        val h = AddressOrder.bare(host)
        val pct = h.indexOf('%')
        if (pct < 0 || AddressOrder.family(h) != AddressOrder.Family.V6) return InetAddress.getByName(h)
        return try {
            InetAddress.getByName(h)
        } catch (e: UnknownHostException) {
            val bytes = InetAddress.getByName(h.substring(0, pct)).address
            val scope = h.substring(pct + 1)
            scope.toIntOrNull()?.let { Inet6Address.getByAddress(null, bytes, it) }
                ?: Inet6Address.getByAddress(null, bytes, NetworkInterface.getByName(scope) ?: throw e)
        }
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun pairErrorText(reason: String): String = when (reason) {
        "bad_code" -> "Wrong code (pair_error: bad_code). Check the code on the computer and try again."
        "not_pairing" -> "The computer is not in pairing mode (pair_error: not_pairing). Start pairing on it first."
        else -> "The computer refused to pair (pair_error: $reason)."
    }

    private fun describe(t: Throwable, host: String, port: Int): String {
        if (isCertificateProblem(t)) return "The computer at $host has a different certificate than when it was paired. Pair again."
        return when (t) {
            is ConnectException -> "Could not reach $host:$port (is Caw Desktop running?)"
            is SocketTimeoutException -> "$host:$port did not answer in time"
            is UnknownHostException -> "Unknown address $host"
            is SSLHandshakeException -> "Secure connection to $host:$port failed: ${t.message}"
            else -> "$host:$port: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun publish() {
        val d = desktop
        val s = State(d, enabled && d != null, connection, session?.outbox?.size ?: 0, problem, pointerUnavailable)
        if (s == state) return
        state = s
        main.post { observers.forEach { it.onState(s) } }
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** One TLS connection. Writes happen on the CawRemote thread; reads on its own thread. */
    private class Conn(val socket: SSLSocket, val framer: LineFramer) : RemoteSession.Wire {
        private val out: OutputStream = socket.outputStream
        var ticker: ScheduledFuture<*>? = null

        override fun write(line: String): Boolean = try {
            out.write(LineFramer.encode(line))
            out.flush()
            true
        } catch (e: IOException) {
            close("write failed: ${e.message}")
            false
        }

        override fun close(reason: String) {
            shut()
            control.execute { dropped(this, reason) }
        }

        fun shut() {
            ticker?.cancel(false)
            try {
                socket.close()
            } catch (e: IOException) {
            }
        }

        fun readLoop() {
            var reason = "the computer closed the connection"
            try {
                val input = socket.inputStream
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (line in framer.feed(buf, 0, n)) {
                        control.execute { if (conn === this) session?.onLine(line, now()) }
                    }
                }
            } catch (e: IOException) {
                reason = e.message ?: e.javaClass.simpleName
            }
            control.execute { dropped(this, reason) }
        }
    }
}
