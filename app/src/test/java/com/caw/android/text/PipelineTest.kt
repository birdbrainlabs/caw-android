package com.caw.android.text

import com.caw.android.speech.RecognizerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Realistic raw model output (ALL CAPS, as the streaming zipformer writes it) pushed through every
 * text step between the recognizer and the field: join + tidy -> cleaner -> word fixes -> commit string.
 * Uses a small inline list of test word fixes (the bundled seed ships empty).
 */
class PipelineTest {

    private val fixes = WordFixes(
        listOf(
            WordFix("akme|ackme", "acme", Capitalization.ALWAYS),
            WordFix("colour", "color"),
        ),
    )
    private val pipeline = TextPipeline()

    /** [segments] are the recognizer's banked segments, the last one being the live one. */
    private fun run(vararg segments: String): String {
        val raw = RecognizerText.tidy(RecognizerText.join(segments.toList()))
        val out = pipeline.run(raw, fixes)
        // Steps may drop whole words (fillers, repeats, stutters) but never invent or alter one:
        // every word that comes out was a word that went in, so no transposed letters, no run-together words.
        val heard = words(raw).toSet()
        for (w in words(out.cleaned)) assertTrue("\"$w\" was not in \"$raw\"", w in heard)
        return out.fixed
    }

    private fun words(s: String) =
        s.lowercase().split(Regex("""[^\p{L}'-]+""")).flatMap { it.split("-") }.filter { it.isNotEmpty() }

    @Test
    fun thisIsWorkingNow() = assertEquals("This is working now.", run("THIS IS WORKING NOW"))

    @Test
    fun iAmWorkingOnIt() = assertEquals("I am working on it.", run("I AM WORKING ON IT"))

    @Test
    fun repeatedWorkingCollapses() =
        assertEquals("It is working fine.", run("IT IS WORKING WORKING FINE"))

    @Test
    fun loneLetterBeforeTheWordIsKept() {
        // No hyphen, so it is not a stutter fragment; it must come through untouched.
        assertEquals("W working.", run("W WORKING"))
    }

    @Test
    fun hyphenatedStutterFragmentIsDropped() = assertEquals("Working.", run("W-WORKING"))

    @Test
    fun twoBankedSegmentsKeepTheirSpace() =
        assertEquals("This is working.", run("THIS IS", "WORKING"))

    @Test
    fun segmentsWithStraySpacesFromTheModel() =
        assertEquals("This is working.", run(" THIS IS ", " WORKING", ""))

    @Test
    fun manySegmentsAndARunOnBreak() {
        val out = run(
            "SO I WAS TRYING TO GET THE KEYBOARD WORKING ON THE PHONE",
            "AND I THINK IT IS WORKING NOW",
        )
        assertEquals("So I was trying to get the keyboard working on the phone. And I think it is working now.", out)
        assertTrue(!out.contains("  "))
    }

    @Test
    fun wordFixesStillApply() = assertEquals("Ask Acme about the color.", run("ASK AKME ABOUT THE COLOUR"))

    @Test
    fun commitStringSpacing() {
        assertEquals("This is working. ", TextPipeline.commitString(null, "This is working."))
        assertEquals("This is working. ", TextPipeline.commitString("", "This is working."))
        assertEquals("This is working. ", TextPipeline.commitString(" ", "This is working."))
        assertEquals(" This is working. ", TextPipeline.commitString("x", "This is working."))
        assertEquals(" This is working. ", TextPipeline.commitString(".", "This is working."))
    }
}
