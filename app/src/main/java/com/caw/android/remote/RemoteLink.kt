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
import java.net.InetSocketAddress
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
 * "Send to computer": the connection to the paired Caw Desktop (Caw Remote Protocol).
 * One per process, shared by the input method and [com.caw.android.ComputerActivity].
 *
 * Threads: everything that touches the socket or [RemoteSession] runs on the "CawRemote" thread;
 * each connection has its own reader thread that only hands lines over. Observers are called on
 * the main thread. While the switch is on and a computer is paired, it stays connected: pings
 * every 15 s, reconnects with backoff, and looks the computer up by mDNS when its address fails.
 * The touchpad screen keeps it connected the same way while it is open, switch or not.
 */
object RemoteLink {
    private const val TAG = "CawRemote"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val HANDSHAKE_TIMEOUT_MS = 8_000
    private const val PAIR_ANSWER_TIMEOUT_MS = 15_000
    /** The touchpad keeps the connection this long after it closes, so turning the phone does not drop it. */
    private const val TOUCHPAD_LINGER_MS = 10_000L
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
    /** The touchpad screen is open (or closed less than [TOUCHPAD_LINGER_MS] ago). */
    private var touchpad = false
    private var touchpadLinger: ScheduledFuture<*>? = null
    private val movesLog = DeltaLog("pointer_move")
    private val scrollsLog = DeltaLog("scroll")
    private var pointerLogFlush: ScheduledFuture<*>? = null

    /** Stay connected: Send to computer is on, or the touchpad is open. */
    private val wanted: Boolean get() = enabled || touchpad

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

    // ---- touchpad (v2) ----

    /**
     * The touchpad screen opened or closed. While it is open Caw stays connected to the paired
     * computer even with Send to computer off; after it closes the link lingers 10 s (rotation).
     */
    fun setTouchpadOpen(open: Boolean) {
        if (!initialized) return
        control.execute {
            touchpadLinger?.cancel(false)
            touchpadLinger = null
            if (open) {
                touchpad = true
                if (conn == null) {
                    policy.reset()
                    cancelRetry()
                    ensure()
                }
            } else {
                touchpadLinger = control.schedule({
                    touchpadLinger = null
                    touchpad = false
                    ensure()
                }, TOUCHPAD_LINGER_MS, TimeUnit.MILLISECONDS)
            }
        }
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
            if (!wanted) {
                // Just paired with the switch off: hello_ok proved the link; nothing to keep open.
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
        val socket = try {
            open(d.host, d.port, PinnedTrust(d.fingerprintHex))
        } catch (t: Throwable) {
            connectFailed(d, t)
            return
        }
        start(socket, LineFramer())
    }

    private fun connectFailed(d: PairedDesktop, t: Throwable) {
        problem = describe(t, d.host, d.port)
        Log.i(TAG, "connect ${d.host}:${d.port} failed: $problem")
        val decision = policy.onFailure()
        if (decision.discover) {
            val found = DesktopFinder(app).find(d.id)
            if (found != null && (found.host != d.host || found.port != d.port) && desktop?.id == d.id) {
                Log.i(TAG, "found ${d.name} at ${found.host}:${found.port}")
                desktop = d.copy(host = found.host, port = found.port).also { store.saveDesktop(it) }
                scheduleRetry(0)
                return
            }
        }
        scheduleRetry(decision.delayMs)
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
                    desktop = d
                    store.saveDesktop(d)
                    enabled = store.enabled
                    problem = null
                    policy.reset()
                    Log.i(TAG, "paired with $name ($id) at $host:$port")
                    // Same connection carries on with hello (it closes after hello_ok if the switch is off).
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
            raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            raw.tcpNoDelay = true
            raw.keepAlive = true
            val s = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
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

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    private fun pairErrorText(reason: String): String = when (reason) {
        "bad_code" -> "Wrong code (pair_error: bad_code). Check the code on the computer and try again."
        "not_pairing" -> "The computer is not in pairing mode (pair_error: not_pairing). Start pairing on it first."
        else -> "The computer refused to pair (pair_error: $reason)."
    }

    private fun describe(t: Throwable, host: String, port: Int): String {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is CertificateException) {
                return "The computer at $host has a different certificate than when it was paired. Pair again."
            }
            cause = cause.cause
        }
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
