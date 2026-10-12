package com.caw.android.speech

import com.caw.android.text.TextPipeline
import com.caw.android.text.Capitalization
import com.caw.android.text.WordFix
import com.caw.android.text.WordFixes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Moonshine writes mixed case with punctuation. Its segments are joined by
 * [RecognizerText.joinSentences] (never [RecognizerText.tidy]) and then go through the same
 * cleaner and word fixes as before; none of that may damage the model's casing or punctuation.
 */
class MoonshineTextTest {

    private val fixes = WordFixes(
        listOf(
            WordFix("akme|ackme", "acme", Capitalization.ALWAYS),
            WordFix("colour", "color"),
        ),
    )
    private val pipeline = TextPipeline()

    private fun run(vararg segments: String): String =
        pipeline.run(RecognizerText.joinSentences(segments.toList()), fixes).fixed

    @Test
    fun twoSegmentsJoinAsTwoSentences() =
        assertEquals("Okay, trying again. Let's see.", run("Okay, trying again.", "Let's see."))

    @Test
    fun joinAddsAStopAndACapitalBetweenSegments() =
        // The first segment and the last one's ending are left as written; the cleaner finishes them.
        assertEquals("okay trying again. Let's see", RecognizerText.joinSentences(listOf(" okay trying again", "let's see ")))

    @Test
    fun trailingCommaBecomesAStop() =
        assertEquals("Well, I tried. It works.", run("Well, I tried,", "it works."))

    @Test
    fun questionAndExclamationAreKept() =
        assertEquals("Did it work? Yes! It did.", run("Did it work?", "Yes!", "It did."))

    @Test
    fun noDoublePeriods() {
        assertEquals("This is working.", run("This is working."))
        assertEquals("One. Two.", run("One.", "Two."))
        assertEquals("Wait... Okay.", RecognizerText.joinSentences(listOf("Wait...", "Okay.")))
    }

    @Test
    fun properCaseIsNotLowercased() =
        assertEquals("I told Sam about the iPad in New York.", run("I told Sam about the iPad in New York."))

    @Test
    fun lastSegmentWithoutPunctuationGetsAStop() =
        assertEquals("Okay. Let's see.", run("Okay.", "Let's see"))

    @Test
    fun emptySegmentsAreSkipped() =
        assertEquals("Okay. Let's see.", run("", " Okay. ", "  ", "Let's see."))

    @Test
    fun wordFixesStillApplyToMixedCase() =
        assertEquals("Ask Acme about the color.", run("Ask Akme about the colour."))

    @Test
    fun streamingTidyIsUnchanged() =
        assertEquals("I think so", RecognizerText.tidy("I THINK SO"))
}
