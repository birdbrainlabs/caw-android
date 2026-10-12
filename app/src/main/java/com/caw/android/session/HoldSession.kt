package com.caw.android.session

fun interface Clock {
    fun nowMs(): Long
}

/**
 * One hold-to-talk recording, timed as in docs/port-spec.md section 3.
 * Pure Kotlin. Call everything from one thread, and call [tick] about every 50 ms while [isActive].
 *
 * press -> LISTENING -> release -> TAIL (mic keeps running 400 ms) -> FINISHING (mic stopped,
 * waiting for the recognizer's final text) -> done. Finishing is capped at 6 s after release;
 * then the banked live text is delivered as-is.
 */
class HoldSession(private val clock: Clock, private val host: Host) {

    interface Host {
        /** Start the microphone and a fresh recognizer stream. */
        fun startCapture()

        /** Stop the microphone and ask the recognizer for its final text; answer via [onFinal]. */
        fun stopCapture()

        /** Stop everything and drop any result still on its way. */
        fun cancelCapture()

        fun showStatus(status: Status)

        /** Raw transcript, before cleaning. Never blank. */
        fun deliver(text: String)
    }

    enum class Phase { IDLE, LISTENING, TAIL, FINISHING }

    companion object {
        const val MAX_HOLD_MS = 300_000L
        const val TAIL_MS = 400L
        const val FINALIZE_CAP_MS = 6_000L
        const val SILENCE_PEAK = 0.005f
    }

    var phase = Phase.IDLE
        private set

    val isActive: Boolean get() = phase != Phase.IDLE

    private val completedSegments = mutableListOf<String>()
    private var currentSegment = ""
    private var pressedAt = 0L
    /** Clock time of the last release (or max-length stop); for timing release-to-commit. */
    var releasedAt = 0L
        private set
    private var maxLengthHit = false
    private var frames = 0L
    private var peak = 0f
    private var micCovered = false

    /** Everything heard so far: banked segments plus the live one. */
    val rawTranscript: String
        get() = (completedSegments + currentSegment)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /** Returns true if this press started or resumed listening. */
    fun press(): Boolean = when (phase) {
        Phase.IDLE -> {
            completedSegments.clear()
            currentSegment = ""
            frames = 0L
            peak = 0f
            micCovered = false
            maxLengthHit = false
            phase = Phase.LISTENING
            pressedAt = clock.nowMs()
            host.showStatus(Status.LISTENING)
            host.startCapture()
            true
        }
        // Pressed again within the 400 ms tail: the mic is still running, keep going.
        Phase.TAIL -> if (maxLengthHit) {
            false
        } else {
            phase = Phase.LISTENING
            host.showStatus(Status.LISTENING)
            true
        }
        else -> false
    }

    fun release() {
        if (phase != Phase.LISTENING) return
        phase = Phase.TAIL
        releasedAt = clock.nowMs()
        host.showStatus(Status.FINISHING)
    }

    fun onAudio(frameCount: Int, peakLevel: Float) {
        if (!isActive) return
        frames += frameCount
        if (peakLevel > peak) peak = peakLevel
    }

    fun onPartial(incoming: String) {
        if (!isActive) return
        // Text shrank by more than half: the recognizer started a new utterance. Keep the old one.
        if (currentSegment.isNotEmpty() && incoming.length * 2 < currentSegment.length) bank()
        currentSegment = incoming
    }

    /**
     * The recognizer's final text. [micCovered]: the hold's sound looked like a covered mic
     * (MicCoverHint); if no words came out at all, the user is told "I can't hear you".
     */
    fun onFinal(text: String, micCovered: Boolean = false) {
        if (phase != Phase.FINISHING) return
        this.micCovered = micCovered
        val live = rawTranscript
        // The final covers only the recognizer's current stream; banked segments came before it.
        val finalText = (completedSegments + text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        // A final result shorter than what was already heard never wins.
        complete(if (finalText.length >= live.length) finalText else live)
    }

    /**
     * The recognizer failed while being fed and the host has already restarted its stream.
     * Banks what was heard and keeps the hold going (spec 3.2).
     */
    fun onStreamError(message: String) {
        if (!isActive) return
        bank()
        host.showStatus(Status.startFailed(message))
    }

    /** Microphone or recognizer failure. Keeps any words already heard. */
    fun onError(message: String) {
        if (!isActive) return
        host.cancelCapture()
        bank()
        if (rawTranscript.isNotEmpty()) {
            complete(rawTranscript)
        } else {
            phase = Phase.IDLE
            host.showStatus(Status.startFailed(message))
        }
    }

    /**
     * The screen that started this hold went away for good (the IME service was destroyed): stop
     * the mic and drop the hold without a status or any text.
     */
    fun cancel() {
        if (!isActive) return
        phase = Phase.IDLE
        host.cancelCapture()
    }

    fun tick() {
        val now = clock.nowMs()
        if (phase == Phase.LISTENING && now - pressedAt >= MAX_HOLD_MS) {
            maxLengthHit = true
            phase = Phase.TAIL
            releasedAt = now
            host.showStatus(Status.MAX_LENGTH)
        }
        if (phase == Phase.TAIL && now - releasedAt >= TAIL_MS) {
            phase = Phase.FINISHING
            host.stopCapture()
        }
        if ((phase == Phase.TAIL || phase == Phase.FINISHING) && now - releasedAt >= FINALIZE_CAP_MS) {
            host.cancelCapture()
            complete(rawTranscript)
        }
    }

    private fun bank() {
        val trimmed = currentSegment.trim()
        if (trimmed.isNotEmpty()) completedSegments += trimmed
        currentSegment = ""
    }

    private fun complete(text: String) {
        phase = Phase.IDLE
        val trimmed = text.trim()
        when {
            trimmed.isNotEmpty() -> {
                host.showStatus(Status.TRANSCRIPT_READY)
                host.deliver(trimmed)
            }
            frames == 0L -> host.showStatus(Status.NO_AUDIO)
            peak < SILENCE_PEAK -> host.showStatus(Status.ONLY_SILENCE)
            micCovered -> host.showStatus(Status.MIC_COVERED)
            else -> host.showStatus(Status.DIDNT_CATCH)
        }
    }
}
