package com.caw.android.speech

import android.content.res.AssetManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide speech state. Android destroys the IME service every time the user switches back to
 * their keyboard, so the model, its worker and the mic live here and are never closed: the next
 * mic-key press finds the model already loaded.
 *
 * Threads: [recognizer] is only used on the worker (via [onWorker]): loading, creating the stream,
 * feeding, finishing and resetting all happen there, one task at a time, in the order they were
 * posted. [mic] and [feed] are only touched on the main thread. [feed] is the current hold's audio;
 * a result whose feed is no longer current belongs to a finished hold and is dropped.
 */
object SpeechEngine {

    private const val TAG = "CawSpeech"

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "caw-speech") }

    @Volatile var recognizer: Recognizer? = null
        private set

    @Volatile var loadError: String? = null
        private set

    var mic: MicCapture? = null

    var feed: AudioFeed? = null

    /** True once loading has finished, whether it worked or not. */
    val isLoaded: Boolean get() = recognizer != null || loadError != null

    /** Loads the model on the worker if it is not loaded yet; then runs [then] on the worker. */
    fun load(assets: AssetManager, then: (() -> Unit)? = null) {
        onWorker {
            if (recognizer == null) {
                try {
                    val started = System.nanoTime()
                    // Moonshine when its files are in the APK, else the streaming zipformer.
                    val moonshine = MoonshineRecognizer.isBundled(assets)
                    recognizer = if (moonshine) MoonshineRecognizer(assets) else SherpaStreamingRecognizer(assets)
                    loadError = null
                    val which = if (moonshine) "moonshine-base-en" else "sherpa-streaming-en"
                    Log.i(TAG, "speech model loaded in ${(System.nanoTime() - started) / 1_000_000} ms ($which)")
                } catch (t: Throwable) {
                    loadError = t.message ?: t.javaClass.simpleName
                    Log.e(TAG, "speech model did not load", t)
                }
            }
        }
        if (then != null) onWorker(then)
    }

    /** Runs [task] on the worker. A rejected or failing task is logged, never thrown. */
    fun onWorker(task: () -> Unit) {
        try {
            worker.execute {
                try {
                    task()
                } catch (t: Throwable) {
                    Log.e(TAG, "speech task failed", t)
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "speech task rejected", e)
        }
    }

    // ---- mic level (for Crumb's mouth) ----

    /** Main thread. Gets the smoothed mic level 0..1, at most [LEVEL_MIN_GAP_MS] apart. */
    var onLevel: ((Float) -> Unit)? = null

    private const val LEVEL_MIN_GAP_MS = 33L // no more than 30 per second
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var latestLevel = 0f
    private val levelPosted = AtomicBoolean(false)
    private var levelDeliveredAt = 0L // main thread only

    private val deliverLevel: Runnable = object : Runnable {
        override fun run() {
            val wait = levelDeliveredAt + LEVEL_MIN_GAP_MS - SystemClock.uptimeMillis()
            if (wait > 0) {
                mainHandler.postDelayed(this, wait)
                return
            }
            levelDeliveredAt = SystemClock.uptimeMillis()
            levelPosted.set(false)
            onLevel?.invoke(latestLevel)
        }
    }

    /** Any thread (the mic thread). Keeps only the newest value; one post in flight at a time. */
    fun postLevel(level: Float) {
        latestLevel = level
        if (levelPosted.compareAndSet(false, true)) mainHandler.post(deliverLevel)
    }
}
