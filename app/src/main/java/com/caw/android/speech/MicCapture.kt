package com.caw.android.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 16 kHz mono microphone capture on its own thread. Callbacks arrive on that thread.
 *
 * The mic thread only reads AudioRecord and hands each 100 ms chunk to [Listener.onSamples], which
 * must return at once (queue it; never decode here). A thread that falls behind lets AudioRecord's
 * ring buffer overflow, and the lost samples are not reported anywhere.
 */
class MicCapture(private val listener: Listener, private val replay: FloatArray? = null) {

    interface Listener {
        /** AudioRecord is running, [openMs] after [start] was called. */
        fun onRecording(openMs: Long) {}

        /** [peak] and [rms] are 0..1 for this chunk. Must not block. */
        fun onSamples(samples: FloatArray, peak: Float, rms: Float)

        /** AudioRecord.read returned the negative [code]. */
        fun onReadError(code: Int) {}

        /** The mic is closed; it recorded for [recordedMs] of wall time. Runs before stop's callback. */
        fun onClosed(recordedMs: Long) {}

        fun onError(message: String)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK = 1_600 // 100 ms
        /** 2 s of 16-bit mono: room for the mic thread to be held up without losing audio. */
        private const val MIN_BUFFER_BYTES = SAMPLE_RATE * 2 * 2
        private const val MAX_ERRORS_IN_A_ROW = 5
        private const val LEVEL_RISE = 0.6f
        private const val LEVEL_FALL = 0.35f
    }

    @Volatile
    private var running = false

    @Volatile
    private var onStopped: (() -> Unit)? = null

    private var startedAt = 0L

    /** Mic level for Crumb's mouth: min(1, rms * 3), smoothed per chunk. Mic thread only. */
    private var level = 0f

    fun start() {
        running = true
        startedAt = SystemClock.elapsedRealtime()
        Thread({ loop() }, "caw-mic").start()
    }

    /**
     * Ends capture after the current chunk, plus whatever is still waiting in AudioRecord's buffer;
     * [then] runs on the mic thread once the mic is closed and every chunk has gone to the listener.
     */
    fun stop(then: (() -> Unit)?) {
        onStopped = then
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (replay != null) {
            replayLoop(replay)
            return
        }
        var record: AudioRecord? = null
        var recordingSince = -1L
        try {
            val minBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) {
                listener.onError("the microphone is not available")
                return
            }
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBytes, MIN_BUFFER_BYTES),
            )
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                listener.onError("the microphone could not be opened")
                return
            }
            rec.startRecording()
            recordingSince = SystemClock.elapsedRealtime()
            listener.onRecording(recordingSince - startedAt)
            val buffer = ShortArray(CHUNK)
            var errorsInARow = 0
            while (running) {
                val n = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    listener.onReadError(n)
                    errorsInARow++
                    if (n == AudioRecord.ERROR_DEAD_OBJECT || errorsInARow >= MAX_ERRORS_IN_A_ROW) {
                        listener.onError("the microphone stopped (error $n)")
                        break
                    }
                    continue
                }
                errorsInARow = 0
                if (n > 0) deliver(buffer, n)
            }
            // Released: take what AudioRecord already holds before stop() throws it away.
            if (!running) {
                var spare = SAMPLE_RATE * 2 / CHUNK + 1
                while (spare-- > 0) {
                    val n = rec.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    if (n <= 0) break
                    deliver(buffer, n)
                }
            }
        } catch (t: Throwable) {
            listener.onError(t.message ?: t.javaClass.simpleName)
        } finally {
            try {
                try {
                    record?.stop()
                } catch (e: IllegalStateException) {
                    // never started
                }
                record?.release()
                level = 0f
                SpeechEngine.postLevel(0f)
                listener.onClosed(if (recordingSince < 0) -1 else SystemClock.elapsedRealtime() - recordingSince)
                onStopped?.invoke()
            } catch (t: Throwable) {
                listener.onError(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Debug builds only (see DebugBench "replay"): delivers [samples] in 100 ms chunks at real-time
     * pace instead of opening AudioRecord, then waits for the release. Nothing comes after the WAV.
     */
    private fun replayLoop(samples: FloatArray) {
        val since = SystemClock.elapsedRealtime()
        try {
            listener.onRecording(since - startedAt)
            var i = 0
            while (running) {
                Thread.sleep(CHUNK * 1000L / SAMPLE_RATE)
                if (i < samples.size) {
                    val end = min(i + CHUNK, samples.size)
                    deliver(samples.copyOfRange(i, end))
                    i = end
                }
            }
        } catch (t: Throwable) {
            listener.onError(t.message ?: t.javaClass.simpleName)
        } finally {
            level = 0f
            SpeechEngine.postLevel(0f)
            listener.onClosed(SystemClock.elapsedRealtime() - since)
            onStopped?.invoke()
        }
    }

    private fun deliver(buffer: ShortArray, n: Int) {
        deliver(FloatArray(n) { buffer[it] / 32768f })
    }

    private fun deliver(samples: FloatArray) {
        val n = samples.size
        var peak = 0f
        var sum = 0.0
        for (i in 0 until n) {
            val s = samples[i]
            val a = abs(s)
            if (a > peak) peak = a
            sum += (s * s).toDouble()
        }
        val rms = sqrt(sum / n).toFloat()
        listener.onSamples(samples, peak, rms)
        // Fast rise, slower fall, so the mouth opens on a syllable and does not flicker shut.
        val target = min(1f, rms * 3f)
        level += (target - level) * (if (target > level) LEVEL_RISE else LEVEL_FALL)
        SpeechEngine.postLevel(level)
    }
}
