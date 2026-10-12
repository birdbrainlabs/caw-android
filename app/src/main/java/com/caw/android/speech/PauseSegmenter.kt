package com.caw.android.speech

/**
 * Cuts one hold's audio into segments at pauses, so an offline recognizer (Moonshine) can decode
 * each finished segment while the hold goes on. Pure Kotlin; works on 100 ms chunks and their RMS.
 * The caller keeps the audio itself, in a queue parallel to this one, and applies each [Step].
 *
 * Rules (docs/port-spec.md section 5):
 * - A chunk is quiet when its RMS is below max([FLOOR], [RELATIVE] x the average RMS of the loud
 *   chunks in this segment so far); otherwise it is loud.
 * - Speech = the stretch from the segment's first loud chunk to its last loud chunk. Once that is at
 *   least [MIN_SPEECH_CHUNKS] (1.0 s) long and [PAUSE_CHUNKS] (0.8 s) of quiet have followed it, the
 *   segment ends: it keeps [PAD_CHUNKS] (0.2 s) of the quiet after the speech, the next segment starts
 *   with the last 0.2 s of the quiet, and the quiet between is dropped.
 * - A segment that reaches [MAX_CHUNKS] (25 s) is cut at the quietest 0.4 s in its last 5 s: the first
 *   0.2 s of that stretch ends this segment, the second 0.2 s starts the next one.
 * - Before a segment has any loud chunk, only its last 0.2 s is kept (leading silence is dropped).
 */
class PauseSegmenter {

    /**
     * What the caller does with its buffered chunks after a [push]: hand the first [emit] chunks to
     * the recognizer as one finished segment (none if 0), then throw away the first [drop] chunks
     * ([drop] >= [emit]). The chunks left over begin the next segment.
     */
    data class Step(val emit: Int, val drop: Int) {
        val isNothing: Boolean get() = emit == 0 && drop == 0
    }

    companion object {
        const val CHUNK_SAMPLES = 1_600 // 100 ms at 16 kHz
        const val FLOOR = 0.004f
        const val RELATIVE = 0.15f
        const val MIN_SPEECH_CHUNKS = 10 // 1.0 s
        const val PAUSE_CHUNKS = 8 // 0.8 s
        const val PAD_CHUNKS = 2 // 0.2 s
        const val MAX_CHUNKS = 250 // 25 s
        const val FORCE_LOOKBACK_CHUNKS = 50 // 5 s

        private val NOTHING = Step(0, 0)
    }

    /** RMS of every chunk buffered for the current segment, oldest first. */
    private val levels = ArrayList<Float>()
    private var loudCount = 0
    private var loudSum = 0.0
    private var firstLoud = -1
    private var lastLoud = -1

    /** Chunks buffered for the current segment. */
    val size: Int get() = levels.size

    /** True if the buffered segment has any chunk above the silence floor (worth decoding). */
    val hasSound: Boolean get() = levels.any { it >= FLOOR }

    fun reset() {
        levels.clear()
        clearStats()
    }

    /** Adds one 100 ms chunk's RMS; returns what to do with the buffered chunks. */
    fun push(rms: Float): Step {
        add(rms)
        val n = levels.size

        if (firstLoud < 0) {
            // No speech yet: keep only the last 0.2 s.
            if (n > PAD_CHUNKS) {
                val drop = n - PAD_CHUNKS
                repeat(drop) { levels.removeAt(0) }
                return Step(0, drop)
            }
            return NOTHING
        }

        val speech = lastLoud - firstLoud + 1
        val quietAfter = n - 1 - lastLoud
        if (speech >= MIN_SPEECH_CHUNKS && quietAfter >= PAUSE_CHUNKS) {
            val emit = lastLoud + 1 + PAD_CHUNKS
            val drop = n - PAD_CHUNKS
            return cut(emit, drop)
        }

        if (n >= MAX_CHUNKS) {
            // Quietest 0.4 s (4 chunks) whose start lies in the last 5 s.
            val window = PAD_CHUNKS * 2
            var best = n - window
            var bestSum = Double.MAX_VALUE
            for (start in maxOf(0, n - FORCE_LOOKBACK_CHUNKS)..(n - window)) {
                var sum = 0.0
                for (i in start until start + window) sum += levels[i]
                if (sum < bestSum) { // first (earliest) quietest wins ties
                    bestSum = sum
                    best = start
                }
            }
            val boundary = best + PAD_CHUNKS
            return cut(boundary, boundary)
        }
        return NOTHING
    }

    private fun cut(emit: Int, drop: Int): Step {
        val rest = levels.subList(drop, levels.size).toList()
        levels.clear()
        clearStats()
        // The carried chunks begin the next segment; re-measure them under its own fresh threshold.
        for (r in rest) add(r)
        return Step(emit, drop)
    }

    private fun add(rms: Float) {
        val threshold = maxOf(FLOOR, if (loudCount == 0) 0f else RELATIVE * (loudSum / loudCount).toFloat())
        levels.add(rms)
        if (rms >= threshold) {
            loudCount++
            loudSum += rms
            if (firstLoud < 0) firstLoud = levels.size - 1
            lastLoud = levels.size - 1
        }
    }

    private fun clearStats() {
        loudCount = 0
        loudSum = 0.0
        firstLoud = -1
        lastLoud = -1
    }
}
