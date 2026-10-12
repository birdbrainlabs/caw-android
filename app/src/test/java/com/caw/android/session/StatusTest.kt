package com.caw.android.session

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusTest {

    @Test
    fun readyIsPlainWithoutAComputer() {
        assertEquals(Status.READY, Status.readyFor(null, sending = false, connected = false, waiting = 0))
        // Paired but the switch is off.
        assertEquals(Status.READY, Status.readyFor("omarchy", sending = false, connected = true, waiting = 0))
    }

    @Test
    fun readyNamesTheComputerWhileSending() {
        assertEquals("Sending to omarchy — hold to talk", Status.readyFor("omarchy", true, true, 0).text)
        assertEquals("Not connected to omarchy", Status.readyFor("omarchy", true, false, 0).text)
        assertEquals("Not connected to omarchy — 2 messages waiting", Status.readyFor("omarchy", true, false, 2).text)
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
        assertEquals("Connected to omarchy", Status.touchpadLink("omarchy", true, false).text)
        assertEquals("Reconnecting to omarchy…", Status.touchpadLink("omarchy", false, false).text)
        assertEquals(StatusKind.WARNING, Status.touchpadLink("omarchy", false, false).kind)
        assertEquals(
            "Connected to omarchy, but it can't move the mouse (pointer_unavailable)",
            Status.touchpadLink("omarchy", true, true).text,
        )
        // Not connected wins: the flag is from the last connection.
        assertEquals("Reconnecting to omarchy…", Status.touchpadLink("omarchy", false, true).text)
    }
}
