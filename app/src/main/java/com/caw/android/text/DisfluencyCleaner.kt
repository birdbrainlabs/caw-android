package com.caw.android.text

import java.text.Normalizer

/** Each switch turns one step of the cleaner on or off (docs/port-spec.md 2.1). All default on. */
data class CleanerSwitches(
    val spellsOutSmallOrdinals: Boolean = true,
    val removesStutterFragments: Boolean = true,
    val removesFillerWords: Boolean = true,
    val collapsesRepeatedWords: Boolean = true,
    val removesClauseCommas: Boolean = true,
    val breaksRunOnSentences: Boolean = true,
    val capitalizesSentences: Boolean = true,
    val appliesOwnerSpellings: Boolean = true,
    val addsTerminalPunctuation: Boolean = true,
)

/** Port of the Mac TranscriptDisfluencyCleaner. Steps run in the exact order of spec 2.2. */
class DisfluencyCleaner(val switches: CleanerSwitches = CleanerSwitches()) {

    /** [wordFixes] is step 9 (the user's word fixes); it runs after capitalisation, before the full stop. */
    fun clean(raw: String, wordFixes: (String) -> String = { it }): String {
        var text = raw
        if (switches.spellsOutSmallOrdinals) text = spellOutOrdinals(text)
        if (switches.removesStutterFragments) text = removeStutters(text)
        if (switches.removesFillerWords) text = removeFillers(text)
        if (switches.collapsesRepeatedWords) text = collapseRepeats(text)
        text = normalizeSpacing(text)
        if (switches.removesClauseCommas) text = CLAUSE_COMMA.replace(text, " ")
        if (switches.breaksRunOnSentences) text = breakRunOns(text)
        if (switches.capitalizesSentences) text = capitalizeSentences(text)
        if (switches.appliesOwnerSpellings) text = wordFixes(text)
        if (switches.addsTerminalPunctuation) text = addTerminalPunctuation(text)
        return text
    }

    // 1
    private fun spellOutOrdinals(input: String): String {
        if (input.none { it.isDigit() }) return input
        var text = input
        for ((regex, word) in ORDINALS) text = regex.replace(text) { word }
        return text
    }

    // 2a, 2b, 2c
    private fun removeStutters(input: String): String {
        var text = INLINE_STUTTER.replace(input) { m ->
            val pieces = m.value.split("-")
            val last = fold(pieces.last())
            if (pieces.size > 1 && pieces.dropLast(1).all { last.startsWith(fold(it)) }) pieces.last() else m.value
        }
        for (pass in 0 until 8) {
            val next = SEPARATED_STUTTER.replace(text) { m -> dropIfPrefix(m) }
            if (next == text) break
            text = next
        }
        return TRAILING_OFF.replace(text) { m -> dropIfPrefix(m) }
    }

    private fun dropIfPrefix(m: MatchResult): String =
        if (fold(m.groupValues[2]).startsWith(fold(m.groupValues[1]))) "" else m.value

    // 3
    private fun removeFillers(input: String): String {
        val text = PARENTHETICAL_FILLER.replace(input) { "," }
        return FILLER.replace(text) { "" }
    }

    // 4a, 4b, 4c
    private fun collapseRepeats(input: String): String {
        var text = input
        for (pass in 0 until 4) {
            val next = REPEATED_PHRASE.replace(text) { m ->
                val phrase = m.groupValues[1]
                // "New York, New York" survives.
                if (m.value.contains(",") && phrase.first().isUpperCase()) m.value else phrase
            }
            if (next == text) break
            text = next
        }
        text = PUNCTUATED_REPEAT.replace(text) { it.groupValues[1] }
        return REPEATED_WORD.replace(text) { it.groupValues[1] }
    }

    // 5
    private fun normalizeSpacing(input: String): String {
        var text = SPACE_BEFORE_PUNCTUATION.replace(input) { it.groupValues[1] }
        text = REPEATED_COMMA.replace(text) { ", " }
        text = COMMA_AFTER_SENTENCE_END.replace(text) { it.groupValues[1] + " " }
        text = LEADING_COMMA.replace(text) { "" }
        text = LEADING_PUNCTUATION.replace(text) { "" }
        text = REPEATED_SPACE.replace(text) { " " }
        text = SPACE_BEFORE_NEWLINE.replace(text) { "" } // as on the Mac: the newline goes too
        return text.trim()
    }

    // 7
    private fun breakRunOns(text: String): String {
        val words = text.split(" ").filter { it.isNotEmpty() }.toMutableList()
        if (words.size <= 10) return text
        var distance = 0
        for (index in words.indices) {
            val previous = if (index > 0) words[index - 1] else ""
            if (previous.endsWith(".") || previous.endsWith("?") || previous.endsWith("!")) distance = 0
            val word = core(words[index])
            val remaining = words.size - index
            val previousEndsPlain = previous.isNotEmpty() && previous.last().isLetterOrDigit()
            val breakHere = distance >= 9 &&
                remaining >= 3 &&
                word in STARTERS &&
                previousEndsPlain &&
                core(previous) !in BLOCKED_BEFORE &&
                ((word != "and" && word != "so") ||
                    (index + 1 < words.size && core(words[index + 1]) in CONTINUERS))
            if (breakHere) {
                words[index - 1] = "$previous."
                distance = 0
            } else {
                distance += 1
            }
        }
        return words.joinToString(" ")
    }

    // 8
    private fun capitalizeSentences(text: String): String =
        SENTENCE_START.replace(text) { m -> m.value.dropLast(1) + m.value.last().uppercaseChar() }

    // 10
    private fun addTerminalPunctuation(text: String): String =
        if (text.isNotEmpty() && text.last().isLetterOrDigit()) "$text." else text

    companion object {
        // Kotlin adds UNICODE_CASE whenever IGNORE_CASE is set, as the spec requires.
        private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

        private val MARKS = Regex("""\p{M}+""")

        /** Lower-case with diacritics removed, for comparing stutter fragments. */
        fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

        private fun core(word: String): String = word.lowercase().filter { it.isLetter() }

        private val ORDINALS = listOf(
            "1st" to "first", "2nd" to "second", "3rd" to "third", "4th" to "fourth", "5th" to "fifth",
            "6th" to "sixth", "7th" to "seventh", "8th" to "eighth", "9th" to "ninth", "10th" to "tenth",
        ).map { (digits, word) -> rx("""(?<![0-9A-Za-z])$digits(?![0-9A-Za-z])""") to word }

        private val INLINE_STUTTER = rx("""\b(?:\p{L}{1,3}-){1,}\p{L}+\b""")
        private val SEPARATED_STUTTER = rx("""\b(\p{L}{1,3})-[\t ]+(?=(\p{L}+)\b)""")
        private val TRAILING_OFF =
            rx("""\b(\p{L}[\p{L}\p{N}'’]*)[\t ]*(?:\.{2,}|…)[\t ]+(?=(\p{L}[\p{L}\p{N}'’]*))""")

        private val PARENTHETICAL_FILLER = rx(""",\s*u(?:h+m*|m+)\s*,""")
        private val FILLER = rx("""(?<![\p{L}\p{N}'’\-])u(?:h+m*|m+)(?![\p{L}\p{N}'’\-])""")

        private val REPEATED_PHRASE = rx(
            """\b((?:\p{L}[\p{L}\p{N}'’]*)(?:[\t ]+\p{L}[\p{L}\p{N}'’]*){1,11})[\t ]*,?[\t ]+\1(?![\p{L}\p{N}'’])"""
        )
        private val PUNCTUATED_REPEAT = rx(
            """\b((?:\p{L}[\p{L}\p{N}'’]*)(?:[\t ]+\p{L}[\p{L}\p{N}'’]*){0,11})\b(?:[\t ]*[.,][\t ]+\1\b){2,}"""
        )
        private val REPEATED_WORD = rx("""\b(\p{L}[\p{L}\p{N}]*(?:['’][\p{L}\p{N}]+)?)\b(?:[\t ]*,?[\t ]+\1\b)+""")

        private val SPACE_BEFORE_PUNCTUATION = rx("""[\t ]+([,.;:!?])""")
        private val REPEATED_COMMA = rx(""",(?:[\t ]*,)+[\t ]*""")
        private val COMMA_AFTER_SENTENCE_END = rx("""([.!?])[\t ]*,[\t ]*""")
        private val LEADING_COMMA = rx("""(?m)^[\t ]*,[\t ]*""")
        private val LEADING_PUNCTUATION = rx("""\A[\t ]*[.,;:!?]+[\t ]*""")
        private val REPEATED_SPACE = rx("""[\t ]{2,}""")
        private val SPACE_BEFORE_NEWLINE = rx("""[\t ]+\n""")

        private val CLAUSE_COMMA = rx(""",\s+(?=(?:and|but)\b)""")
        private val SENTENCE_START = rx("""(?:\A|(?<=[.!?])[\t ]+|(?<=\n))\p{Ll}""")

        private val STARTERS = setOf("i", "and", "so", "we", "lets", "if", "why", "what", "please")
        private val CONTINUERS = setOf(
            "i", "we", "it", "the", "that", "this", "then", "you", "my", "a", "an", "im", "lets", "were",
            "maybe", "now", "theres",
        )
        private val BLOCKED_BEFORE = setOf(
            "and", "but", "or", "so", "that", "which", "if", "when", "because", "as", "than", "then", "like",
            "to", "of", "in", "on", "at", "for", "with", "from", "by", "about", "into", "over", "after",
            "before", "while", "where", "a", "an", "the", "my", "your", "his", "her", "its", "our", "their",
            "me", "do", "does", "did", "can", "could", "will", "would", "should", "is", "are", "was", "am",
            "be", "been", "not", "dont", "didnt", "cant", "wont", "let", "make", "makes", "help", "tell",
            "told", "ask", "asked", "say", "said", "think", "know", "see", "want", "need", "mean", "guess",
            "hope", "wish", "sure", "how", "who", "why", "what", "maybe",
        )
    }
}
