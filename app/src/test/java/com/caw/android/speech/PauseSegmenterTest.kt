package com.caw.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [PauseSegmenter] the way MoonshineRecognizer does: a parallel list of chunk ids stands in
 * for the audio, and every Step is applied to it. Levels: speech 0.05, room quiet 0.001.
 */
class PauseSegmenterTest {

    private val speech = 0.05f
    private val quiet = 0.001f

    private class Driver {
        val seg = PauseSegmenter()
        val buffer = ArrayDeque<Int>()
        val segments = mutableListOf<List<Int>>()
        var next = 0

        fun push(level: Float, times: Int = 1) {
            repeat(times) {
                buffer.addLast(next++)
                val step = seg.push(level)
                if (step.emit > 0) segments += buffer.take(step.emit)
                repeat(step.drop) { buffer.removeFirst() }
                assertEquals(seg.size, buffer.size)
            }
        }
    }

    @Test
    fun leadingSilenceKeepsOnlyTheLastPointTwoSeconds() {
        val d = Driver()
        d.push(quiet, 30)
        assertEquals(listOf(28, 29), d.buffer.toList())
        assertFalse(d.seg.hasSound)
        assertTrue(d.segments.isEmpty())
    }

    @Test
    fun pauseAfterOneSecondOfSpeechCutsWithPointTwoSecondsEachSide() {
        val d = Driver()
        d.push(quiet, 5) // ids 0-4; 3 and 4 are kept
        d.push(speech, 12) // ids 5-16
        d.push(quiet, 7) // ids 17-23: not yet 0.8 s
        assertTrue(d.segments.isEmpty())
        d.push(quiet) // id 24: 0.8 s of quiet
        assertEquals(1, d.segments.size)
        // 0.2 s before the speech (3, 4), the speech (5-16), 0.2 s after it (17, 18).
        assertEquals((3..18).toList(), d.segments[0])
        // The next segment starts with the last 0.2 s of the quiet.
        assertEquals(listOf(23, 24), d.buffer.toList())
    }

    @Test
    fun shortUtteranceDoesNotCut() {
        val d = Driver()
        d.push(speech, 6) // 0.6 s
        d.push(quiet, 20)
        assertTrue(d.segments.isEmpty())
        assertTrue(d.seg.hasSound)
        d.push(speech, 6) // the speech span now reaches past 1 s
        d.push(quiet, 8)
        assertEquals(1, d.segments.size)
        assertEquals(0, d.segments[0].first())
    }

    @Test
    fun shortGapsInsideSpeechDoNotCut() {
        val d = Driver()
        repeat(5) {
            d.push(speech, 8)
            d.push(quiet, 7) // 0.7 s gap: under 0.8 s
        }
        assertTrue(d.segments.isEmpty())
    }

    @Test
    fun thresholdFollowsTheLoudnessOfTheSegment() {
        // Loud speaker (0.2): 0.02 is under 0.15 x 0.2 = 0.03, so it counts as quiet.
        val d = Driver()
        d.push(0.2f, 15)
        d.push(0.02f, 8)
        assertEquals(1, d.segments.size)
        // Soft speaker (0.02): the same 0.02 is speech.
        val soft = Driver()
        soft.push(0.02f, 15)
        soft.push(0.02f, 8)
        assertTrue(soft.segments.isEmpty())
    }

    @Test
    fun floorAppliesWhenEverythingIsQuiet() {
        val d = Driver()
        d.push(0.003f, 40) // under the 0.004 floor: never speech
        assertTrue(d.segments.isEmpty())
        assertEquals(2, d.buffer.size)
    }

    @Test
    fun forceCutAtTwentyFiveSecondsAtTheQuietestSpot() {
        val d = Driver()
        // 25 s of speech with one dip (0.01) at ids 220-223, inside the last 5 s.
        for (i in 0 until 250) d.push(if (i in 220..223) 0.01f else speech)
        assertEquals(1, d.segments.size)
        // First 0.2 s of the quiet stretch ends this segment, the second 0.2 s starts the next.
        assertEquals((0..221).toList(), d.segments[0])
        assertEquals((222..249).toList(), d.buffer.toList())
    }

    @Test
    fun forceCutNeverLooksBeforeTheLastFiveSeconds() {
        val d = Driver()
        for (i in 0 until 250) d.push(if (i in 100..103) 0.006f else speech)
        assertEquals(1, d.segments.size)
        assertTrue(d.segments[0].size >= 200)
        assertTrue(d.segments[0].size <= 248)
    }

    @Test
    fun segmentAfterAForceCutCanStillEndAtAPause() {
        val d = Driver()
        d.push(speech, 260)
        assertEquals(1, d.segments.size)
        d.push(quiet, 8)
        assertEquals(2, d.segments.size)
        // No chunk is in two segments, and nothing spoken was dropped.
        val all = d.segments.flatten()
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.containsAll((0 until 260).toList()))
    }

    @Test
    fun resetForgetsEverything() {
        val d = Driver()
        d.push(speech, 5)
        d.seg.reset()
        assertEquals(0, d.seg.size)
        assertFalse(d.seg.hasSound)
    }
}
