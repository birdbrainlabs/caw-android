package com.caw.android.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WordFixStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val seed = """{"version":1,"fixes":[{"from":"teh","to":"the"}]}"""
    private var now = 0L

    private fun store() = WordFixStore(tmp.root, readSeed = { seed }, clock = { now })

    @Test
    fun firstRunCopiesTheSeed() {
        val s = store()
        assertEquals("the", s.current().apply("teh"))
        assertTrue(s.file.exists())
    }

    @Test
    fun existingFileIsNeverOverwritten() {
        val s = store()
        s.file.writeText("""{"fixes":[{"from":"akme","to":"acme","capitalization":"always"}]}""")
        assertEquals("Acme", s.current().apply("akme"))
    }

    @Test
    fun editsAreReloaded() {
        val s = store()
        s.current()
        s.file.writeText("""{"fixes":[{"from":"colour","to":"color"}]}""")
        now += 100
        assertEquals("the", s.current().apply("teh")) // within 0.5 s: not re-checked yet
        now += 500
        assertEquals("color", s.current().apply("colour"))
    }

    @Test
    fun brokenFileKeepsThePreviousList() {
        val s = store()
        s.current()
        s.file.writeText("{ not json")
        now += 1_000
        assertEquals("the", s.current().apply("teh"))
        assertTrue(s.problem!!.startsWith("word-fixes.json could not be read ("))
    }

    @Test
    fun invalidEntriesAreReported() {
        val s = store()
        s.file.writeText("""{"fixes":[{"from":"(","to":"x"},{"from":"*","to":"y"}]}""")
        s.current()
        assertEquals("Skipped 2 fix(es) that could not be read: (, *", s.problem)
    }

    @Test
    fun saveWritesOnlyNonDefaultKeys() {
        val s = store()
        s.save(listOf(WordFix("colour", "color"), WordFix("hatch", "hutch", wholeWord = false, endings = "")))
        val text = s.file.readText()
        assertTrue(text.endsWith("\n"))
        val doc = WordFixJson.parse(text)
        assertEquals(2, doc.fixes.size)
        assertEquals(WordFixJson.DEFAULT_ABOUT, doc.about)
        assertFalse(doc.fixes[1].wholeWord)
        assertEquals("", doc.fixes[1].endings)
        assertFalse(text.contains("\"wholeWord\": true"))
        assertFalse(text.contains("'s|s"))
        assertEquals("color", s.current().apply("colour"))
        assertNull(s.problem)
    }
}
