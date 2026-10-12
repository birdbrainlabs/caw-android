package com.caw.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class MicCoverHintTest {

    private val rate = AudioFeed.SAMPLE_RATE

    private fun sine(hz: Double, amp: Double, seconds: Double = 2.0) =
        FloatArray((rate * seconds).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    private fun FloatArray.plus(other: FloatArray) = FloatArray(size) { this[it] + other[it] }

    /** Runs [samples] through an AudioFeed in 100 ms chunks, as the mic thread does. */
    private fun statsOf(samples: FloatArray): AudioFeed.Stats {
        val feed = AudioFeed()
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + 1_600, samples.size)
            feed.capture(samples.copyOfRange(i, end))
            i = end
        }
        return feed.stats()
    }

    private fun flagged(samples: FloatArray): Boolean = statsOf(samples).let { MicCoverHint.covered(it.rms, it.voiceRms) }

    @Test
    fun rumbleAloneIsACoveredMic() {
        val stats = statsOf(sine(10.0, 0.06))
        assertTrue(MicCoverHint.covered(stats.rms, stats.voiceRms))
        assertTrue(stats.voiceRms < 0.02f * stats.rms) // two passes leave about (10/150)^2 of 10 Hz
    }

    @Test
    fun rumbleWithAVoiceBandToneIsNot() {
        assertFalse(flagged(sine(10.0, 0.06).plus(sine(1_000.0, 0.02))))
    }

    @Test
    fun nearSilenceIsNot() {
        val random = Random(7)
        assertFalse(flagged(FloatArray(2 * rate) { (random.nextDouble(-0.002, 0.002)).toFloat() }))
    }

    @Test
    fun aPlainVoiceBandToneIsNot() {
        val stats = statsOf(sine(1_000.0, 0.02))
        assertFalse(MicCoverHint.covered(stats.rms, stats.voiceRms))
        assertEquals(stats.rms, stats.voiceRms, stats.rms * 0.1f) // 1 kHz keeps about 92 % through both passes
    }

    // Synthetic stand-ins for the holds measured on a phone (MicCoverHint's comment).

    /** 10 Hz plus 15 Hz, scaled so the raw RMS is about 0.038, as the measured covered holds were. */
    private fun measuredRumble(): FloatArray = sine(10.0, 0.038).plus(sine(15.0, 0.038))

    private fun faintNoise(): FloatArray {
        val random = Random(11)
        return FloatArray(2 * rate) { random.nextDouble(-0.001, 0.001).toFloat() }
    }

    @Test
    fun theMeasuredCoveredCaseIsFlagged() {
        val stats = statsOf(measuredRumble().plus(faintNoise()))
        assertEquals(0.038f, stats.rms, 0.003f)
        assertTrue(MicCoverHint.covered(stats.rms, stats.voiceRms))
    }

    @Test
    fun speechLikeHarmonicsOverTheSameRumbleAreNot() {
        val voice = sine(120.0, 0.03).plus(sine(240.0, 0.02)).plus(sine(360.0, 0.015)).plus(sine(480.0, 0.01))
        assertFalse(flagged(voice.plus(measuredRumble())))
    }

    @Test
    fun logLine() {
        assertEquals("hint: mic covered (rms 0.039, voice 0.002)", MicCoverHint.logLine(0.0389f, 0.0018f))
    }
}
