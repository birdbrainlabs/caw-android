package com.caw.android.speech

/** Pure text steps of the recognizers, kept apart so unit tests can run them without a model. */
object RecognizerText {

    // The English zipformer writes ALL CAPS; Caw lowercases it and restores the word "I".
    // Sentence capitals are added later by the cleaner.
    private val STANDALONE_I = Regex("""\bi\b""")

    /** Banked segments plus the live one, trimmed, empty ones dropped, joined by one space. */
    fun join(segments: List<String>): String =
        segments.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /** Streaming zipformer only (ALL CAPS in). Moonshine's mixed case must not go through this. */
    fun tidy(text: String): String = STANDALONE_I.replace(text.lowercase()) { "I" }

    /**
     * Moonshine: each segment was cut at a pause, so it is its own sentence. Segments are trimmed
     * and joined by one space; a segment that ends without . ! ? (or an ellipsis) gets a full stop
     * when another one follows (a trailing comma, colon or semicolon becomes that full stop), and a
     * segment that starts with a lower-case letter after one is capitalised. The text inside a
     * segment, and the last segment's ending, are left exactly as the model wrote them.
     */
    fun joinSentences(segments: List<String>): String {
        val parts = segments.map { it.trim() }.filter { it.isNotEmpty() }
        val out = StringBuilder()
        for ((i, part) in parts.withIndex()) {
            var p = part
            if (i > 0) {
                out.append(' ')
                val first = p.indexOfFirst { it.isLetterOrDigit() }
                if (first >= 0 && p[first].isLowerCase()) {
                    p = p.substring(0, first) + p[first].uppercaseChar() + p.substring(first + 1)
                }
            }
            if (i < parts.size - 1) p = endSentence(p)
            out.append(p)
        }
        return out.toString()
    }

    private val SOFT_END = Regex("""[,;:\-–—]+$""")

    private fun endSentence(s: String): String {
        val t = SOFT_END.replace(s, "").trimEnd()
        if (t.isEmpty()) return s
        val last = t.last()
        // Closing quotes or brackets after the stop count as ended too: 'He said "go."'
        val core = t.trimEnd('"', '\'', '”', '’', ')', ']')
        if (core.isNotEmpty() && core.last() in ".!?…") return t
        return if (last.isLetterOrDigit() || last in "\"'”’)]") "$t." else t
    }
}
