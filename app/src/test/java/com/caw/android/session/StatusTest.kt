package com.caw.android.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {

    @Test
    fun readyIsPlainWithoutAComputer() {
        assertEquals(Status.READY, Status.readyFor(null, sending = false, connected = false, waiting = 0))
        // Paired but the switch is off.
        assertEquals(Status.READY, Status.readyFor("my-laptop", sending = false, connected = true, waiting = 0))
    }

    @Test
    fun readyNamesTheComputerWhileSending() {
        assertEquals("Sending to my-laptop — hold to talk", Status.readyFor("my-laptop", true, true, 0).text)
        assertEquals("Not connected to my-laptop", Status.readyFor("my-laptop", true, false, 0).text)
        assertEquals("Not connected to my-laptop — 2 messages waiting", Status.readyFor("my-laptop", true, false, 2).text)
    }

    @Test
    fun existingStatusWordsAreUnchanged() {
        assertEquals("Ready — hold to talk", Status.READY.text)
        assertEquals("Didn't catch that — try again", Status.DIDNT_CATCH.text)
        assertEquals("I can't hear you", Status.MIC_COVERED.text)
    }

    @Test
    fun touchpadLinkLine() {
        assertEquals(Status.TOUCHPAD_NOT_PAIRED, Status.touchpadLink(null, connected = false, pointerUnavailable = false))
        assertEquals("Connected to my-laptop", Status.touchpadLink("my-laptop", true, false).text)
        assertEquals("Reconnecting to my-laptop…", Status.touchpadLink("my-laptop", false, false).text)
        assertEquals(StatusKind.WARNING, Status.touchpadLink("my-laptop", false, false).kind)
        assertEquals(
            "Connected to my-laptop, but it can't move the mouse (pointer_unavailable)",
            Status.touchpadLink("my-laptop", true, true).text,
        )
        // Not connected wins: the flag is from the last connection.
        assertEquals("Reconnecting to my-laptop…", Status.touchpadLink("my-laptop", false, true).text)
    }
}
