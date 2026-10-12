package com.caw.android.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedTextTest {

    private class FakeComputer(override var sending: Boolean) : FinishedText.Computer {
        val sent = mutableListOf<String>()
        override fun send(text: String) {
            sent += text
        }
    }

    private val local = mutableListOf<String>()

    @Test
    fun screenNotSendingKeepsTextLocallyOnly() {
        val computer = FakeComputer(sending = false)
        val routed = FinishedText(computer).dispatch("Hello.", FinishedText.Local.ALWAYS) { local += it }
        assertEquals(listOf("Hello."), local)
        assertTrue(computer.sent.isEmpty())
        assertTrue(routed.local)
        assertNull(routed.computerText)
    }

    @Test
    fun screenSendingKeepsTextLocallyAndSendsIt() {
        val computer = FakeComputer(sending = true)
        val routed = FinishedText(computer).dispatch("Hello.", FinishedText.Local.ALWAYS) { local += it }
        assertEquals(listOf("Hello."), local)
        assertEquals(listOf("Hello. "), computer.sent)
        assertTrue(routed.local)
        assertEquals("Hello. ", routed.computerText)
    }

    @Test
    fun imeNotSendingGoesToThePhoneField() {
        val computer = FakeComputer(sending = false)
        val routed = FinishedText(computer).dispatch("Hello.", FinishedText.Local.ONLY_WHEN_NOT_SENDING) { local += it }
        assertEquals(listOf("Hello."), local)
        assertTrue(computer.sent.isEmpty())
        assertTrue(routed.local)
    }

    @Test
    fun imeSendingLeavesThePhoneFieldAlone() {
        val computer = FakeComputer(sending = true)
        val routed = FinishedText(computer).dispatch("Hello.", FinishedText.Local.ONLY_WHEN_NOT_SENDING) { local += it }
        assertTrue(local.isEmpty())
        assertEquals(listOf("Hello. "), computer.sent)
        assertFalse(routed.local)
        assertEquals("Hello. ", routed.computerText)
    }

    @Test
    fun computerTextIsSpacedAsForAnEmptyField() {
        // No leading space, one trailing space, every time: Caw cannot see the computer's text.
        val computer = FakeComputer(sending = true)
        val ft = FinishedText(computer)
        ft.dispatch("One.", FinishedText.Local.ALWAYS) {}
        ft.dispatch("Two.", FinishedText.Local.ALWAYS) {}
        assertEquals(listOf("One. ", "Two. "), computer.sent)
        assertEquals("One. ", FinishedText.forComputer("One."))
    }

    @Test
    fun switchIsReadAtEachText() {
        val computer = FakeComputer(sending = false)
        val ft = FinishedText(computer)
        ft.dispatch("Before.", FinishedText.Local.ALWAYS) { local += it }
        computer.sending = true
        ft.dispatch("After.", FinishedText.Local.ALWAYS) { local += it }
        assertEquals(listOf("Before.", "After."), local)
        assertEquals(listOf("After. "), computer.sent)
    }

    @Test
    fun screenTranscriptAndComputerGetTheSameHolds() {
        // The dictation screen's whole path: transcript spacing locally, empty-field spacing to the computer.
        val computer = FakeComputer(sending = true)
        val ft = FinishedText(computer)
        val transcript = Transcript()
        ft.dispatch("Hello there.", FinishedText.Local.ALWAYS) { transcript.append(it) }
        ft.dispatch("Next line.", FinishedText.Local.ALWAYS) { transcript.append(it) }
        assertEquals("Hello there. Next line. ", transcript.text)
        assertEquals(listOf("Hello there. ", "Next line. "), computer.sent)
    }
}
