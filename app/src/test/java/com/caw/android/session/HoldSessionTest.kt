package com.caw.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HoldSessionTest {

    private class FakeClock(var now: Long = 0L) : Clock {
        override fun nowMs() = now
    }

    private class FakeHost : HoldSession.Host {
        val calls = mutableListOf<String>()
        val statuses = mutableListOf<Status>()
        val delivered = mutableListOf<String>()
        override fun startCapture() { calls += "start" }
        override fun stopCapture() { calls += "stop" }
        override fun cancelCapture() { calls += "cancel" }
        override fun showStatus(status: Status) { statuses += status }
        override fun deliver(text: String) { delivered += text }
    }

    private lateinit var clock: FakeClock
    private lateinit var host: FakeHost
    private lateinit var session: HoldSession

    @Before
    fun setUp() {
        clock = FakeClock()
        host = FakeHost()
        session = HoldSession(clock, host)
    }

    private fun advance(ms: Long) {
        clock.now += ms
        session.tick()
    }

    @Test
    fun pressStartsListening() {
        assertTrue(session.press())
        assertEquals(listOf("start"), host.calls)
        assertEquals(Status.LISTENING, host.statuses.last())
        assertEquals(HoldSession.Phase.LISTENING, session.phase)
    }

    @Test
    fun micKeepsRunningForTheTailAfterRelease() {
        session.press()
        session.onAudio(1600, 0.3f)
        session.release()
        assertEquals(Status.FINISHING, host.statuses.last())
        advance(399)
        assertFalse("stop" in host.calls)
        advance(1)
        assertTrue("stop" in host.calls)
        assertEquals(HoldSession.Phase.FINISHING, session.phase)
    }

    @Test
    fun finalTextIsDelivered() {
        session.press()
        session.onPartial("hello")
        session.release()
        advance(400)
        session.onFinal("hello there")
        assertEquals(listOf("hello there"), host.delivered)
        assertEquals(Status.TRANSCRIPT_READY, host.statuses.last())
        assertFalse(session.isActive)
    }

    @Test
    fun partialThatShrinksByMoreThanHalfIsBanked() {
        session.press()
        session.onPartial("hello there my friend")
        session.onPartial("ok")
        assertEquals("hello there my friend ok", session.rawTranscript)
    }

    @Test
    fun partialThatGrowsReplacesTheLiveSegment() {
        session.press()
        session.onPartial("hello")
        session.onPartial("hello there")
        assertEquals("hello there", session.rawTranscript)
    }

    @Test
    fun shorterFinalNeverReplacesWhatWasHeard() {
        session.press()
        session.onPartial("one two three four")
        session.release()
        advance(400)
        session.onFinal("one two")
        assertEquals(listOf("one two three four"), host.delivered)
    }

    @Test
    fun finalisingIsCappedAtSixSecondsAndLiveTextIsUsed() {
        session.press()
        session.onPartial("first part")
        session.onPartial("x") // banks "first part"
        session.onPartial("second")
        session.release()
        advance(400)
        advance(5_599)
        assertTrue(host.delivered.isEmpty())
        advance(1)
        assertEquals(listOf("first part second"), host.delivered)
        assertTrue("cancel" in host.calls)
        assertFalse(session.isActive)

        session.onFinal("late result") // ignored
        assertEquals(1, host.delivered.size)
    }

    @Test
    fun holdIsCutAtFiveMinutes() {
        session.press()
        session.onPartial("a long story")
        advance(299_999)
        assertEquals(HoldSession.Phase.LISTENING, session.phase)
        advance(1)
        assertEquals(Status.MAX_LENGTH, host.statuses.last())
        assertEquals(HoldSession.Phase.TAIL, session.phase)
        session.release() // the finger lifts later: nothing changes
        assertEquals(Status.MAX_LENGTH, host.statuses.last())
        advance(400)
        assertTrue("stop" in host.calls)
        session.onFinal("a long story")
        assertEquals(listOf("a long story"), host.delivered)
    }

    @Test
    fun pressingAgainDuringTheTailKeepsListening() {
        session.press()
        session.release()
        advance(200)
        assertTrue(session.press())
        assertEquals(HoldSession.Phase.LISTENING, session.phase)
        assertEquals(listOf("start"), host.calls)
    }

    @Test
    fun pressWhileFinishingIsIgnored() {
        session.press()
        session.release()
        advance(400)
        assertFalse(session.press())
    }

    @Test
    fun noTextStatuses() {
        session.press()
        session.release()
        advance(400)
        session.onFinal("")
        assertEquals(Status.NO_AUDIO, host.statuses.last())

        session.press()
        session.onAudio(1600, 0.001f)
        session.release()
        advance(400)
        session.onFinal("")
        assertEquals(Status.ONLY_SILENCE, host.statuses.last())

        session.press()
        session.onAudio(1600, 0.4f)
        session.release()
        advance(400)
        session.onFinal("  ")
        assertEquals(Status.DIDNT_CATCH, host.statuses.last())
        assertTrue(host.delivered.isEmpty())
    }

    @Test
    fun coveredMicWithNoWordsSaysICantHearYou() {
        session.press()
        session.onAudio(1600, 0.25f)
        session.release()
        advance(400)
        session.onFinal("", micCovered = true)
        assertEquals(Status.MIC_COVERED, host.statuses.last())
        assertEquals("I can't hear you", host.statuses.last().text)
        assertTrue(host.delivered.isEmpty())
        assertFalse(session.isActive)

        // The next hold starts clean: no words and not covered is the usual warning.
        session.press()
        session.onAudio(1600, 0.25f)
        session.release()
        advance(400)
        session.onFinal("")
        assertEquals(Status.DIDNT_CATCH, host.statuses.last())
    }

    @Test
    fun coveredMicWithWordsStillDelivers() {
        session.press()
        session.onAudio(1600, 0.25f)
        session.onPartial("some words")
        session.release()
        advance(400)
        session.onFinal("", micCovered = true)
        assertEquals(listOf("some words"), host.delivered)
        assertEquals(Status.TRANSCRIPT_READY, host.statuses.last())
    }

    @Test
    fun errorWithNothingHeardShowsStartFailure() {
        session.press()
        session.onError("no model")
        assertEquals(Status.startFailed("no model"), host.statuses.last())
        assertTrue("cancel" in host.calls)
        assertFalse(session.isActive)
    }

    @Test
    fun errorKeepsWordsAlreadyHeard() {
        session.press()
        session.onPartial("keep these words")
        session.onError("mic broke")
        assertEquals(listOf("keep these words"), host.delivered)
    }

    @Test
    fun streamErrorBanksTextAndKeepsListening() {
        session.press()
        session.onPartial("first words")
        session.onStreamError("decoder failed")
        assertEquals(Status.startFailed("decoder failed"), host.statuses.last())
        assertEquals(HoldSession.Phase.LISTENING, session.phase)
        assertFalse("cancel" in host.calls)
        // The restarted stream reports only new text; the banked words stay.
        session.onPartial("more")
        assertEquals("first words more", session.rawTranscript)
        session.release()
        advance(400)
        session.onFinal("more words")
        assertEquals(listOf("first words more words"), host.delivered)
    }

    @Test
    fun cancelDropsTheHoldWithoutStatusOrText() {
        session.press()
        session.onPartial("half a sentence")
        val statuses = host.statuses.size
        session.cancel()
        assertEquals(HoldSession.Phase.IDLE, session.phase)
        assertEquals("cancel", host.calls.last())
        assertEquals(statuses, host.statuses.size)
        advance(10_000)
        assertTrue(host.delivered.isEmpty())
        // A fresh press starts a new hold.
        assertTrue(session.press())
    }
}
