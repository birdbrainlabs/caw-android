package com.caw.android.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordFixesTest {

    private fun fix(vararg fixes: WordFix) = WordFixes(fixes.toList())

    @Test
    fun alwaysCapitalises() {
        val f = fix(WordFix("clawed|clod", "claude", Capitalization.ALWAYS))
        assertEquals("ask Claude now", f.apply("ask clawed now"))
        assertEquals("ask Claude's help", f.apply("ask clawed's help"))
    }

    @Test
    fun matchHeardFollowsTheHeardFirstLetter() {
        val f = fix(WordFix("palate", "palette", Capitalization.MATCH_HEARD))
        assertEquals("Palette and palette", f.apply("Palate and palate"))
    }

    @Test
    fun exactNeverChangesCase() {
        val f = fix(WordFix("ipod|eyepod", "iPod", Capitalization.EXACT))
        assertEquals("iPod rocks", f.apply("Eyepod rocks"))
    }

    @Test
    fun wholeWordOnlyMatchesWholeWords() {
        val f = fix(WordFix("cn", "cyan"))
        assertEquals("cnn and cyan", f.apply("cnn and cn"))
    }

    @Test
    fun wholeWordOffMatchesInsideWords() {
        val f = fix(WordFix("hatch", "hutch", wholeWord = false))
        assertEquals("escapehutch", f.apply("escapehatch"))
    }

    @Test
    fun defaultEndingsAreKept() {
        val f = fix(WordFix("chad", "chat"))
        assertEquals("chats", f.apply("chads"))
        assertEquals("Chat's", f.apply("Chad's"))
    }

    @Test
    fun endingsAreLowerCased() {
        val f = fix(WordFix("colonel", "kernel", Capitalization.ALWAYS))
        assertEquals("Kernels", f.apply("COLONELS"))
    }

    @Test
    fun customEndings() {
        val f = fix(WordFix("widget", "widget", Capitalization.EXACT, endings = "s?(?:'s)?"))
        assertEquals("widgets and widget's", f.apply("Widgets and Widget's"))
        val none = fix(WordFix("chad", "chat", endings = ""))
        assertEquals("chads", none.apply("chads"))
    }

    @Test
    fun lookaheadInFrom() {
        val f = fix(WordFix("dock|doc(?='s)", "doug", Capitalization.ALWAYS))
        assertEquals("Doug's docs and Doug", f.apply("doc's docs and dock"))
    }

    @Test
    fun laterEntriesSeeEarlierOutput() {
        val f = fix(WordFix("pier", "peer"), WordFix("peer's", "Peer's", Capitalization.ALWAYS))
        assertEquals("Peer's toy", f.apply("pier's toy"))
    }

    @Test
    fun badAndBlankEntriesAreSkipped() {
        val f = fix(WordFix("(", "x"), WordFix("  ", "y"), WordFix("chad", "chat"))
        assertEquals(listOf("("), f.invalid)
        assertEquals("chat", f.apply("chad"))
    }

    @Test
    fun seedFileLoads() {
        val doc = WordFixJson.parse(File("src/main/assets/word-fixes.json").readText())
        val f = WordFixes(doc.fixes)
        assertEquals(4, doc.fixes.size)
        assertTrue(f.invalid.isEmpty())
        assertEquals("The kernel under the Peartree is on GitHub", f.apply("The colonel under the pear tree is on git hub"))
    }
}
