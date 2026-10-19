package com.caw.android.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {

    @Test
    fun readyIsPlainWithoutAComputer() {
        assertEquals(Status.READY, Status.readyFor(null, sending = false, connected = false, waiting = 0))
        // Paired but the switch is off.
        assertEquals(Status.READY, Status.readyFor("Desk", sending = false, connected = true, waiting = 0))
    }

    @Test
    fun readyNamesTheComputerWhileSending() {
        assertEquals("Sending to Desk — hold to talk", Status.readyFor("Desk", true, true, 0).text)
        assertEquals("Not connected to Desk", Status.readyFor("Desk", true, false, 0).text)
        assertEquals("Not connected to Desk — 2 messages waiting", Status.readyFor("Desk", true, false, 2).text)
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
        assertEquals("Connected to Desk", Status.touchpadLink("Desk", true, false).text)
        assertEquals("Reconnecting to Desk…", Status.touchpadLink("Desk", false, false).text)
        assertEquals(StatusKind.WARNING, Status.touchpadLink("Desk", false, false).kind)
        assertEquals(
            "Connected to Desk, but it can't move the mouse (pointer_unavailable)",
            Status.touchpadLink("Desk", true, true).text,
        )
        // Not connected wins: the flag is from the last connection.
        assertEquals("Reconnecting to Desk…", Status.touchpadLink("Desk", false, true).text)
    }
}
