package com.caw.android.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordFixesTest {

    private fun fix(vararg fixes: WordFix) = WordFixes(fixes.toList())

    @Test
    fun alwaysCapitalises() {
        val f = fix(WordFix("akme|ackme", "acme", Capitalization.ALWAYS))
        assertEquals("ask Acme now", f.apply("ask akme now"))
        assertEquals("ask Acme's help", f.apply("ask akme's help"))
    }

    @Test
    fun matchHeardFollowsTheHeardFirstLetter() {
        val f = fix(WordFix("colour", "color", Capitalization.MATCH_HEARD))
        assertEquals("Color and color", f.apply("Colour and colour"))
    }

    @Test
    fun exactNeverChangesCase() {
        val f = fix(WordFix("teh|tha", "the", Capitalization.EXACT))
        assertEquals("the rocks", f.apply("Teh rocks"))
    }

    @Test
    fun wholeWordOnlyMatchesWholeWords() {
        val f = fix(WordFix("teh", "the"))
        assertEquals("tehran and the", f.apply("tehran and teh"))
    }

    @Test
    fun wholeWordOffMatchesInsideWords() {
        val f = fix(WordFix("hatch", "hutch", wholeWord = false))
        assertEquals("rabbithutch", f.apply("rabbithatch"))
    }

    @Test
    fun defaultEndingsAreKept() {
        val f = fix(WordFix("colour", "color"))
        assertEquals("colors", f.apply("colours"))
        assertEquals("Color's", f.apply("Colour's"))
    }

    @Test
    fun endingsAreLowerCased() {
        val f = fix(WordFix("akme", "acme", Capitalization.ALWAYS))
        assertEquals("Acmes", f.apply("AKMES"))
    }

    @Test
    fun customEndings() {
        val f = fix(WordFix("widget", "widget", Capitalization.EXACT, endings = "s?(?:'s)?"))
        assertEquals("widgets and widget's", f.apply("Widgets and Widget's"))
        val none = fix(WordFix("colour", "color", endings = ""))
        assertEquals("colours", none.apply("colours"))
    }

    @Test
    fun lookaheadInFrom() {
        val f = fix(WordFix("jon|john(?='s)", "jon", Capitalization.ALWAYS))
        assertEquals("Jon's johns and Jon", f.apply("john's johns and jon"))
    }

    @Test
    fun laterEntriesSeeEarlierOutput() {
        val f = fix(WordFix("colour", "color"), WordFix("color's", "Color's", Capitalization.ALWAYS))
        assertEquals("Color's toy", f.apply("colour's toy"))
    }

    @Test
    fun badAndBlankEntriesAreSkipped() {
        val f = fix(WordFix("(", "x"), WordFix("  ", "y"), WordFix("teh", "the"))
        assertEquals(listOf("("), f.invalid)
        assertEquals("the", f.apply("teh"))
    }

    @Test
    fun seedFileLoadsEmpty() {
        // The bundled seed ships empty; the user's own list lives only on the device.
        val doc = WordFixJson.parse(File("src/main/assets/word-fixes.json").readText())
        val f = WordFixes(doc.fixes)
        assertEquals(0, doc.fixes.size)
        assertTrue(f.invalid.isEmpty())
        assertEquals("Ask about the colour", f.apply("Ask about the colour"))
    }
}
