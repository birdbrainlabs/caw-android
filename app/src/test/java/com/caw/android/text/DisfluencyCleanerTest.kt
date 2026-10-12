package com.caw.android.text

import org.junit.Assert.assertEquals
import org.junit.Test

class DisfluencyCleanerTest {

    private val cleaner = DisfluencyCleaner()

    @Test
    fun fillersAndInlineStutters() {
        assertEquals("I want to go.", cleaner.clean("um I w-want to go"))
    }

    @Test
    fun hyphenatedWordsSurvive() {
        assertEquals("A well-known trick.", cleaner.clean("a well-known trick"))
    }

    @Test
    fun separatedStutter() {
        assertEquals("I want it.", cleaner.clean("I w- want it"))
    }

    @Test
    fun trailingOff() {
        assertEquals("There's a cat.", cleaner.clean("there... there's a cat"))
    }

    @Test
    fun parentheticalFiller() {
        assertEquals("So, we left.", cleaner.clean("so, um, we left"))
    }

    @Test
    fun repeatedWordsCollapse() {
        assertEquals("The cat sat.", cleaner.clean("the the cat sat"))
    }

    @Test
    fun repeatedPhraseCollapsesButNewYorkSurvives() {
        assertEquals("I went to the shop.", cleaner.clean("I went to I went to the shop"))
        assertEquals("New York, New York.", cleaner.clean("New York, New York"))
    }

    @Test
    fun smallOrdinalsAreSpelledOut() {
        assertEquals("The second and 11th place.", cleaner.clean("the 2nd and 11th place"))
    }

    @Test
    fun commaBeforeAndIsRemoved() {
        assertEquals("I like apples and pears.", cleaner.clean("I like apples, and pears"))
    }

    @Test
    fun longRunOnIsBroken() {
        assertEquals(
            "I went to the store and bought some milk then we went home. And we slept.",
            cleaner.clean("I went to the store and bought some milk then we went home and we slept"),
        )
    }

    @Test
    fun wordFixesRunAfterCapitalsAndBeforeTheFullStop() {
        val fixes = WordFixes(listOf(WordFix("eyepod", "iPod", Capitalization.EXACT)))
        assertEquals("iPod is here.", cleaner.clean("eyepod is here") { fixes.apply(it) })
    }

    @Test
    fun switchesTurnStepsOff() {
        val plain = DisfluencyCleaner(
            CleanerSwitches(capitalizesSentences = false, addsTerminalPunctuation = false, removesFillerWords = false),
        )
        assertEquals("um hello", plain.clean("um hello"))
    }

    @Test
    fun existingPunctuationIsKept() {
        assertEquals("Is it done?", cleaner.clean("is it done?"))
    }
}
