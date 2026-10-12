package com.caw.android.speech

import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One hold's audio on its way from the mic thread to the recognizer. Pure Kotlin.
 *
 * The mic thread only calls [capture] (never blocks, never decodes). The speech worker, the one
 * thread that touches the recognizer, calls [drain], which feeds every waiting chunk in capture
 * order. The queue is unbounded and nothing is dropped: audio captured before the recognizer
 * stream exists (model still loading, stream not created yet) simply waits for the first [drain].
 * Only [cancel] (the hold was abandoned) discards audio.
 */
class AudioFeed {

    data class Stats(
        val chunksCaptured: Int,
        val chunksFed: Int,
        val samplesFed: Long,
        val maxQueueDepth: Int,
        val readErrors: Int,
        /** Press to AudioRecord running, or -1 if the mic never started. */
        val micStartMs: Long,
        /** Wall time the AudioRecord was recording, or -1 if not known yet. */
        val recordedMs: Long,
        /** Loudest sample 0..1 and overall RMS 0..1 of everything captured. */
        val peak: Float = 0f,
        val rms: Float = 0f,
        /** RMS 0..1 of the [VoiceBand] (two 150 Hz high-passes): the voice, without rumble. */
        val voiceRms: Float = 0f,
    ) {
        val secondsFed: Double get() = samplesFed / SAMPLE_RATE.toDouble()

        fun line(): String = String.format(
            Locale.US,
            "audio: chunks captured %d, fed %d, %.2f s fed, %s recorded, max queue %d, read errors %d, mic start %s, peak %.3f, rms %.4f, voice rms %.4f",
            chunksCaptured, chunksFed, secondsFed,
            if (recordedMs < 0) "?" else String.format(Locale.US, "%.2f s", recordedMs / 1000.0),
            maxQueueDepth, readErrors,
            if (micStartMs < 0) "?" else "$micStartMs ms",
            peak, rms, voiceRms,
        )
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }

    private val queue = LinkedBlockingQueue<FloatArray>()
    private val captured = AtomicInteger()
    private val fed = AtomicInteger()
    private val samplesFed = AtomicLong()
    private val depth = AtomicInteger()
    private val maxDepth = AtomicInteger()
    private val readErrors = AtomicInteger()
    // Loudness, mic thread writes / any thread reads (a stale read is fine for a log line).
    @Volatile private var peak = 0f
    @Volatile private var sumSquares = 0.0
    @Volatile private var samplesCaptured = 0L
    @Volatile private var voiceSumSquares = 0.0
    private val voiceBand = VoiceBand(SAMPLE_RATE) // mic thread only

    @Volatile var micStartMs = -1L
    @Volatile var recordedMs = -1L

    @Volatile var cancelled = false
        private set

    /** Mic thread. Queues one chunk; returns at once. */
    fun capture(chunk: FloatArray) {
        if (cancelled) return
        var p = peak
        var ss = 0.0
        var vs = 0.0
        for (x in chunk) {
            val a = if (x < 0f) -x else x
            if (a > p) p = a
            ss += (x * x).toDouble()
            val v = voiceBand.next(x)
            vs += (v * v).toDouble()
        }
        peak = p
        sumSquares += ss
        voiceSumSquares += vs
        samplesCaptured += chunk.size
        queue.put(chunk)
        captured.incrementAndGet()
        val d = depth.incrementAndGet()
        maxDepth.accumulateAndGet(d) { a, b -> maxOf(a, b) }
    }

    /** Mic thread. AudioRecord.read returned a negative code. */
    fun noteReadError() {
        readErrors.incrementAndGet()
    }

    /**
     * Worker only. Hands every queued chunk to [feed], oldest first, until the queue is empty.
     * [feed] must not throw (catch inside it). Returns how many chunks were fed.
     */
    fun drain(feed: (FloatArray) -> Unit): Int {
        var n = 0
        while (!cancelled) {
            val chunk = queue.poll() ?: break
            depth.decrementAndGet()
            feed(chunk)
            fed.incrementAndGet()
            samplesFed.addAndGet(chunk.size.toLong())
            n++
        }
        return n
    }

    /** The hold was abandoned: stop feeding and let go of the waiting audio. */
    fun cancel() {
        cancelled = true
        queue.clear()
    }

    fun stats() = Stats(
        chunksCaptured = captured.get(),
        chunksFed = fed.get(),
        samplesFed = samplesFed.get(),
        maxQueueDepth = maxDepth.get(),
        readErrors = readErrors.get(),
        micStartMs = micStartMs,
        recordedMs = recordedMs,
        peak = peak,
        rms = if (samplesCaptured == 0L) 0f else kotlin.math.sqrt(sumSquares / samplesCaptured).toFloat(),
        voiceRms = if (samplesCaptured == 0L) 0f else kotlin.math.sqrt(voiceSumSquares / samplesCaptured).toFloat(),
    )
}
