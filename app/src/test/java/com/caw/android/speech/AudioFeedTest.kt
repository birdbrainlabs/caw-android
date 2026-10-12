package com.caw.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AudioFeedTest {

    private fun chunk(id: Int, size: Int = 1_600) = FloatArray(size) { id.toFloat() }

    @Test
    fun audioCapturedBeforeTheStreamExistsIsFedInOrder() {
        val feed = AudioFeed()
        for (i in 0 until 5) feed.capture(chunk(i))
        val got = mutableListOf<Int>()
        assertEquals(5, feed.drain { got += it[0].toInt() })
        assertEquals(listOf(0, 1, 2, 3, 4), got)
        val stats = feed.stats()
        assertEquals(5, stats.chunksCaptured)
        assertEquals(5, stats.chunksFed)
        assertEquals(0.5, stats.secondsFed, 1e-9)
        assertEquals(5, stats.maxQueueDepth)
    }

    @Test
    fun drainOnAnEmptyQueueFeedsNothing() {
        val feed = AudioFeed()
        assertEquals(0, feed.drain { throw AssertionError("nothing to feed") })
        assertEquals(0, feed.stats().maxQueueDepth)
    }

    @Test
    fun maxQueueDepthIsTheLargestBacklog() {
        val feed = AudioFeed()
        feed.capture(chunk(0))
        feed.capture(chunk(1))
        feed.drain { }
        feed.capture(chunk(2))
        feed.drain { }
        assertEquals(2, feed.stats().maxQueueDepth)
        assertEquals(3, feed.stats().chunksFed)
    }

    @Test
    fun aSlowWorkerNeverLosesOrReordersChunks() {
        // Mic thread captures 300 chunks (30 s) while a slow worker drains, as on a slow phone.
        val feed = AudioFeed()
        val worker = Executors.newSingleThreadExecutor()
        val got = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val mic = Thread {
            for (i in 0 until 300) {
                feed.capture(chunk(i, size = 16))
                worker.execute { feed.drain { got += it[0].toInt(); Thread.sleep(0, 200_000) } }
            }
        }
        mic.start()
        mic.join()
        worker.execute { feed.drain { got += it[0].toInt() } }
        worker.shutdown()
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals((0 until 300).toList(), got.toList())
        assertEquals(300, feed.stats().chunksFed)
        assertEquals(300, feed.stats().chunksCaptured)
    }

    @Test
    fun cancelStopsFeedingAndDropsLaterCaptures() {
        val feed = AudioFeed()
        feed.capture(chunk(0))
        feed.capture(chunk(1))
        var fed = 0
        feed.drain {
            fed++
            feed.cancel()
        }
        assertEquals(1, fed)
        feed.capture(chunk(2))
        assertEquals(0, feed.drain { fed++ })
        assertEquals(2, feed.stats().chunksCaptured)
    }

    @Test
    fun readErrorsAreCounted() {
        val feed = AudioFeed()
        feed.noteReadError()
        feed.noteReadError()
        assertEquals(2, feed.stats().readErrors)
    }

    @Test
    fun statsLineNamesEveryFigure() {
        val feed = AudioFeed()
        feed.micStartMs = 180
        feed.recordedMs = 2_450
        repeat(3) { feed.capture(chunk(it)) }
        feed.drain { }
        feed.noteReadError()
        assertEquals(
            "audio: chunks captured 3, fed 3, 0.30 s fed, 2.45 s recorded, max queue 3, read errors 1, mic start 180 ms, peak 2.000, rms 1.2910, voice rms 0.0403",
            feed.stats().line(),
        )
        assertEquals(
            "audio: chunks captured 0, fed 0, 0.00 s fed, ? recorded, max queue 0, read errors 0, mic start ?, peak 0.000, rms 0.0000, voice rms 0.0000",
            AudioFeed().stats().line(),
        )
    }
}
