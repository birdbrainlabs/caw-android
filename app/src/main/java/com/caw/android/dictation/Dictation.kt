package com.caw.android.dictation

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.caw.android.remote.RemoteLink
import com.caw.android.session.HoldSession
import com.caw.android.session.Status
import com.caw.android.speech.AudioFeed
import com.caw.android.speech.DebugBench
import com.caw.android.speech.MicCapture
import com.caw.android.speech.MicCoverHint
import com.caw.android.speech.SpeechEngine
import com.caw.android.text.TextPipeline
import com.caw.android.text.WordFixStore

/**
 * Process-wide hold-to-talk, shared by the places Caw dictates from: the dictation screen
 * ([com.caw.android.DictationActivity], the app itself), the touchpad
 * ([com.caw.android.TouchpadActivity]) and the IME pill
 * ([com.caw.android.CawInputMethodService], for a phone text box). There is one [HoldSession] and
 * one [SpeechEngine] per process, so the model is loaded once and only one hold runs at a time:
 * a press from one [Front] while another front's hold is still running is ignored.
 *
 * The [Front] that pressed owns the hold: it gets the statuses and the finished, cleaned text.
 *
 * Threads: main thread only, except where the capture code posts to the [SpeechEngine] worker
 * (the recognizer) and the mic thread (audio), exactly as the IME did before.
 */
object Dictation : HoldSession.Host {

    private const val TAG = "CawSpeech"
    private const val TICK_MS = 50L

    /** A screen that dictates: the IME pill or the dictation screen. Main thread. */
    interface Front {
        fun showStatus(status: Status)

        /** The cleaned text of a finished hold (never blank). */
        fun deliver(cleaned: String)

        /** Something Crumb shows changed (phase, mic level, model loaded). */
        fun refreshOrb()
    }

    private val main = Handler(Looper.getMainLooper())
    private val engine = SpeechEngine
    private val session = HoldSession({ SystemClock.uptimeMillis() }, this)
    private val pipeline = TextPipeline()
    private lateinit var app: Context
    private lateinit var wordFixes: WordFixStore
    private var initialized = false

    /** The computer side of [FinishedText]: sends while [sending] says so. */
    private class ToComputer(private val wanted: () -> Boolean) : FinishedText.Computer {
        override val sending: Boolean get() = wanted()

        override fun send(text: String) {
            RemoteLink.sendText(text)
            Log.i(TAG, "to computer: \"$text\"${if (RemoteLink.state.connected) "" else " (queued, not connected)"}")
            Log.i(TAG, "release-to-send ms: ${SystemClock.uptimeMillis() - releasedAt}")
        }
    }

    /** The shared "finished text -> computer" routing (see [FinishedText]), while Send to computer is on. */
    val finishedText = FinishedText(ToComputer { RemoteLink.state.sending })

    /**
     * The touchpad screen's routing: it is the computer's screen, so its text goes to the paired
     * computer whether or not the Send to computer switch is on.
     */
    val touchpadText = FinishedText(ToComputer { RemoteLink.state.desktop != null })

    /**
     * Backspace / Enter for the paired computer (a `key` message, count 1), from the pill or the
     * screen while Send to computer is on, or from the touchpad ([whenPaired]) whenever one is paired.
     */
    fun sendKey(key: String, whenPaired: Boolean = false) {
        val ok = if (whenPaired) RemoteLink.state.desktop != null else RemoteLink.state.sending
        if (!ok) return
        RemoteLink.sendKey(key, 1)
        Log.i(TAG, "to computer: key $key")
    }

    /** The front whose hold is running (or ran last). */
    var owner: Front? = null
        private set

    private val fronts = LinkedHashSet<Front>()

    /** Smoothed mic level 0..1 from SpeechEngine, for Crumb's mouth. */
    var micLevel = 0f
        private set

    private var lastStatus: Status = Status.READY

    val phase: HoldSession.Phase get() = session.phase
    val isActive: Boolean get() = session.isActive
    val releasedAt: Long get() = session.releasedAt

    /** This front's view of the phase: IDLE unless the running hold is its own. */
    fun phaseFor(front: Front): HoldSession.Phase = if (owner === front) session.phase else HoldSession.Phase.IDLE

    /** [front]'s own hold is running. */
    fun activeFor(front: Front): Boolean = owner === front && session.isActive

    private val ticker = object : Runnable {
        override fun run() {
            session.tick()
            refreshAll()
            if (session.isActive) main.postDelayed(this, TICK_MS)
        }
    }

    /** Once per process: word fixes, the model (starts loading now), Send to computer. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        app = context.applicationContext
        wordFixes = WordFixStore(
            dir = app.filesDir,
            readSeed = {
                val seed = WordFixStore.seedAsset(app.assets.list("")?.toList().orEmpty())
                app.assets.open(seed).bufferedReader().use { it.readText() }
            },
            post = { main.post(it) },
        )
        // Start loading now (once per process) so the first hold rarely waits.
        engine.load(app.assets)
        engine.onLevel = { level ->
            micLevel = level
            refreshAll()
        }
        // Debug builds only: model speed benchmark on a broadcast (see DebugBench).
        DebugBench.registerIfDebug(app)
        RemoteLink.init(app)
    }

    /** [front] is on screen: it gets orb refreshes. Crumb thinks while the model loads; look again once it has. */
    fun attach(front: Front) {
        fronts += front
        if (!engine.isLoaded) engine.load(app.assets) { main.post { refreshAll() } }
    }

    fun detach(front: Front) {
        fronts -= front
    }

    /**
     * Starts (or, within the 400 ms tail, resumes) a hold for [front]. Returns false if another
     * front's hold is still running. The mic starts now even if the model is still loading: the
     * audio waits in the hold's AudioFeed and is fed, in order, once the recognizer is ready.
     */
    fun press(front: Front): Boolean {
        if (session.isActive && owner !== front) {
            Log.i(TAG, "press ignored: another screen's hold is still running")
            return false
        }
        owner = front
        if (session.press()) {
            main.removeCallbacks(ticker)
            main.post(ticker)
        }
        refreshAll()
        return true
    }

    /** Lets go of [front]'s hold, if it has one listening. Also for its screen going away mid-hold. */
    fun release(front: Front) {
        if (owner !== front) return
        session.release()
        refreshAll()
    }

    /** [front] is gone for good: drop its hold, if any, without a status or text. */
    fun abandon(front: Front) {
        detach(front)
        if (owner !== front) return
        session.cancel()
        owner = null
    }

    private fun refreshAll() {
        for (f in fronts.toList()) f.refreshOrb()
        owner?.let { if (it !in fronts) it.refreshOrb() }
    }

    // ---- HoldSession.Host ----

    override fun startCapture() {
        engine.mic?.stop(null) // never two mics at once
        engine.feed?.cancel()
        val feed = AudioFeed()
        engine.feed = feed
        // Loading is a no-op once the model is in; if it is still loading, the tasks below wait
        // behind it on the worker while the mic keeps queueing audio.
        if (!engine.isLoaded) engine.load(app.assets)
        // Posted before the mic starts, so the stream exists before the first chunk is drained.
        engine.onWorker {
            if (feed.cancelled) return@onWorker
            val r = engine.recognizer
            val error = if (r == null) {
                engine.loadError ?: "the speech model did not load"
            } else {
                try {
                    r.start()
                    null
                } catch (t: Throwable) {
                    t.message ?: t.javaClass.simpleName
                }
            }
            if (error != null) {
                feed.cancel()
                main.post { if (engine.feed === feed) session.onError(error) }
            } else {
                drainOnWorker(feed) // audio captured while the model loaded
            }
        }
        // Debug builds only: a WAV queued by DebugBench ("replay") stands in for the mic on this hold.
        val replay = DebugBench.takeReplay()
        engine.mic = MicCapture(replay = replay, listener = object : MicCapture.Listener {
            override fun onRecording(openMs: Long) {
                feed.micStartMs = openMs
            }

            override fun onSamples(samples: FloatArray, peak: Float, rms: Float) {
                feed.capture(samples)
                engine.onWorker { drainOnWorker(feed) }
                main.post {
                    if (engine.feed === feed) {
                        session.onAudio(samples.size, peak)
                    }
                }
            }

            override fun onReadError(code: Int) {
                feed.noteReadError()
            }

            override fun onClosed(recordedMs: Long) {
                feed.recordedMs = recordedMs
            }

            override fun onError(message: String) {
                main.post { if (engine.feed === feed) session.onError(message) }
            }
        }).also { it.start() }
    }

    /**
     * Worker only. Feeds every chunk this hold has captured so far, in order, then reports the
     * text. A recognizer failure keeps the words heard so far and carries on with a fresh stream
     * (spec 3.2); the chunk that failed is not fed twice.
     */
    private fun drainOnWorker(feed: AudioFeed) {
        if (feed.cancelled) return
        val r = engine.recognizer ?: return
        val fed = feed.drain { chunk ->
            try {
                r.feed(chunk)
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                val heard = r.partial()
                val restarted = try {
                    r.start()
                    true
                } catch (t2: Throwable) {
                    false
                }
                if (!restarted) feed.cancel()
                main.post {
                    if (engine.feed === feed) {
                        session.onPartial(heard)
                        if (restarted) session.onStreamError(message) else session.onError(message)
                    }
                }
            }
        }
        if (fed > 0 && !feed.cancelled) {
            val text = r.partial()
            main.post { if (engine.feed === feed) session.onPartial(text) }
        }
    }

    override fun stopCapture() {
        val feed = engine.feed ?: return
        // Runs after the mic has closed, so the tail and every chunk before it are already queued.
        val askForFinal: () -> Unit = {
            engine.onWorker {
                if (feed.cancelled) return@onWorker
                drainOnWorker(feed)
                val text = if (feed.cancelled) "" else try {
                    engine.recognizer?.finish() ?: ""
                } catch (t: Throwable) {
                    ""
                }
                val stats = feed.stats()
                Log.i(TAG, stats.line())
                val covered = MicCoverHint.covered(stats.rms, stats.voiceRms)
                main.post {
                    if (engine.feed === feed) {
                        session.onFinal(text, covered)
                        if (lastStatus == Status.MIC_COVERED) Log.i(TAG, MicCoverHint.logLine(stats.rms, stats.voiceRms))
                    }
                }
            }
        }
        val m = engine.mic
        engine.mic = null
        if (m != null) m.stop(askForFinal) else askForFinal()
    }

    override fun cancelCapture() {
        engine.mic?.stop(null)
        engine.mic = null
        engine.feed?.let { feed ->
            val stats = feed.stats()
            feed.cancel()
            Log.i(TAG, "cancelled, ${stats.chunksCaptured - stats.chunksFed} chunk(s) never fed; ${stats.line()}")
        }
        engine.feed = null
    }

    override fun showStatus(status: Status) {
        lastStatus = status
        owner?.showStatus(status)
    }

    override fun deliver(text: String) {
        val stages = pipeline.run(text, wordFixes.current())
        Log.i(TAG, "cleaned: \"${stages.cleaned}\"")
        Log.i(TAG, "fixed: \"${stages.fixed}\"")
        val cleaned = stages.fixed
        val front = owner ?: return
        if (cleaned.isBlank()) {
            showStatus(Status.DIDNT_CATCH)
            return
        }
        front.deliver(cleaned)
    }
}
