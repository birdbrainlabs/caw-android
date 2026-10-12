package com.caw.android.text

import org.junit.Assert.assertEquals
import org.junit.Test

class CommitChunksTest {

    private fun chunks(text: String) = CommitChunks.chunks(text)

    @Test
    fun wordsCarryTheirTrailingSpaces() {
        assertEquals(listOf("this ", "is ", "working "), chunks("this is working "))
    }

    @Test
    fun punctuationStaysWithItsWord() {
        assertEquals(listOf("Hi, ", "it's ", "working. ", "Yes! "), chunks("Hi, it's working. Yes! "))
    }

    @Test
    fun loosePunctuationJoinsThePreviousWord() {
        assertEquals(listOf("wait , ", "what "), chunks("wait , what "))
    }

    @Test
    fun finalChunkKeepsTheTrailingSpace() {
        assertEquals("done ", chunks("all done ").last())
    }

    @Test
    fun leadingSpaceRidesOnTheFirstChunk() {
        assertEquals(listOf(" hello ", "there "), chunks(" hello there "))
    }

    @Test
    fun emptyStringGivesNoChunks() {
        assertEquals(emptyList<String>(), chunks(""))
    }

    @Test
    fun chunksJoinBackToTheInput() {
        val inputs = listOf(
            "", " ", "one", "one two", " lead and trail  ", "Hi, it's working. Yes! ",
            "a  double   gap ", "- dash first", "tabs\tand\nnewlines ", "... ",
        )
        for (input in inputs) assertEquals(input, chunks(input).joinToString(""))
    }
}
