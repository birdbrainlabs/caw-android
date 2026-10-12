package com.caw.android.speech

import java.util.Locale
import kotlin.math.PI

/**
 * The "I can't hear you" hint. Pure Kotlin.
 *
 * A finger over the phone's bottom mic records loud rumble with almost nothing above 20 Hz, and
 * Moonshine hears no words in it. Measured on a phone (2026-10-12): covered holds had raw RMS
 * 0.037-0.039 with voice-band RMS ([VoiceBand], above about 150 Hz) only 0.04-0.05 of that; spoken
 * holds kept 0.76.
 * A hold that produced no text, was loud ([MIN_RMS]) and kept less than [MAX_VOICE_RATIO] of its
 * energy in the voice band is reported as a covered mic.
 */
object MicCoverHint {

    /** Cutoff of each of the voice band's two high-pass passes. */
    const val CUTOFF_HZ = 150.0

    /** Raw RMS at or above this counts as loud (quiet holds are left to the silence warnings). */
    const val MIN_RMS = 0.015f

    /** Voice-band RMS below this share of the raw RMS means the energy was rumble. */
    const val MAX_VOICE_RATIO = 0.2f

    /** True if the hold's sound looks like a covered mic. The caller checks that no text came out. */
    fun covered(rms: Float, voiceRms: Float): Boolean =
        rms >= MIN_RMS && voiceRms < MAX_VOICE_RATIO * rms

    fun logLine(rms: Float, voiceRms: Float): String =
        String.format(Locale.US, "hint: mic covered (rms %.3f, voice %.3f)", rms, voiceRms)
}

/**
 * The voice band: two first-order high-passes at [MicCoverHint.CUTOFF_HZ] in a row (12 dB/octave),
 * so rumble below 20 Hz is cut hard while speech passes. One sample at a time. Pure Kotlin.
 */
class VoiceBand(sampleRate: Int) {
    private val first = HighPass(MicCoverHint.CUTOFF_HZ, sampleRate)
    private val second = HighPass(MicCoverHint.CUTOFF_HZ, sampleRate)

    fun next(x: Float): Float = second.next(first.next(x))
}

/** First-order high-pass filter, one sample at a time (y = a * (y' + x - x')). Pure Kotlin. */
class HighPass(cutoffHz: Double, sampleRate: Int) {

    private val a: Float = run {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sampleRate
        (rc / (rc + dt)).toFloat()
    }
    private var lastIn = 0f
    private var lastOut = 0f

    fun next(x: Float): Float {
        lastOut = a * (lastOut + x - lastIn)
        lastIn = x
        return lastOut
    }
}
