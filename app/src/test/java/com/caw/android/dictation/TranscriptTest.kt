package com.caw.android.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    @Test
    fun firstHoldHasNoLeadingSpaceAndOneTrailingSpace() {
        val t = Transcript()
        t.append("Hello there.")
        assertEquals("Hello there. ", t.text)
    }

    @Test
    fun eachHoldFollowsTheLastWithOneSpace() {
        val t = Transcript()
        t.append("Hello there.")
        t.append("How are you?")
        t.append("Fine.")
        assertEquals("Hello there. How are you? Fine. ", t.text)
    }

    @Test
    fun restoredTextWithoutTrailingSpaceGetsALeadingSpace() {
        // Same rule as the IME: a leading space unless the text before ends in whitespace.
        val t = Transcript("Saved words.")
        t.append("More.")
        assertEquals("Saved words. More. ", t.text)
    }

    @Test
    fun restoredTextEndingInANewlineGetsNoLeadingSpace() {
        val t = Transcript("Line one\n")
        t.append("Line two.")
        assertEquals("Line one\nLine two. ", t.text)
    }

    @Test
    fun blankTextIsIgnored() {
        val t = Transcript()
        t.append("   ")
        t.append("")
        assertTrue(t.isEmpty)
        assertEquals("", t.text)
    }

    @Test
    fun surroundingSpacesAreTrimmedBeforeAppending() {
        val t = Transcript()
        t.append("  Hi.  ")
        t.append(" Bye. ")
        assertEquals("Hi. Bye. ", t.text)
    }

    @Test
    fun clearEmptiesAndReturnsTheOldTextForUndo() {
        val t = Transcript()
        t.append("Keep this.")
        val old = t.clear()
        assertEquals("Keep this. ", old)
        assertTrue(t.isEmpty)
        t.restore(old)
        assertEquals("Keep this. ", t.text)
        assertFalse(t.isEmpty)
    }

    @Test
    fun appendAfterClearStartsFresh() {
        val t = Transcript()
        t.append("Old.")
        t.clear()
        t.append("New.")
        assertEquals("New. ", t.text)
    }

    @Test
    fun copyTextIsTheWholeTranscriptAsShown() {
        val t = Transcript()
        t.append("One.")
        t.append("Two.")
        assertEquals("One. Two. ", t.copyText())
        assertEquals(t.text, t.copyText())
    }

    @Test
    fun copyTextOfAnEmptyTranscriptIsEmpty() {
        assertEquals("", Transcript().copyText())
    }
}
