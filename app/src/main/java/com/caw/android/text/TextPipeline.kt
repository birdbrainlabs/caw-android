package com.caw.android.text

/**
 * What happens to the recognizer's text on its way into the field, with every stage kept so it
 * can be logged. [cleaned] is the cleaner alone; [fixed] is the cleaner with the word fixes
 * (they run as its step 9), which is the text that gets inserted.
 */
class TextPipeline(private val cleaner: DisfluencyCleaner = DisfluencyCleaner()) {

    data class Result(val cleaned: String, val fixed: String)

    fun run(raw: String, fixes: WordFixes): Result =
        Result(cleaned = cleaner.clean(raw), fixed = cleaner.clean(raw) { fixes.apply(it) })

    companion object {
        /**
         * The exact string handed to commitText: a leading space unless the field is empty or
         * already ends in whitespace ([before] is the one character before the cursor), then a
         * trailing space.
         */
        fun commitString(before: CharSequence?, text: String): String {
            val lead = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
            return lead + text + " "
        }
    }
}
