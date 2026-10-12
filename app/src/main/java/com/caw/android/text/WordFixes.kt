package com.caw.android.text

import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

enum class Capitalization(val key: String) {
    ALWAYS("always"),
    MATCH_HEARD("matchHeard"),
    EXACT("exact");

    companion object {
        fun parse(key: String?): Capitalization = entries.firstOrNull { it.key == key } ?: MATCH_HEARD
    }
}

/** One user word fix (docs/port-spec.md section 1). [from] and [endings] are regex source. */
data class WordFix(
    val from: String,
    val to: String,
    val capitalization: Capitalization = Capitalization.MATCH_HEARD,
    val wholeWord: Boolean = true,
    val endings: String = DEFAULT_ENDINGS,
    val note: String? = null,
) {
    companion object {
        const val DEFAULT_ENDINGS = "'s|s"
    }
}

/** A compiled list of fixes. Entries run in order, each over the previous one's output. */
class WordFixes(val fixes: List<WordFix>) {

    private val compiled: List<Pair<Pattern, WordFix>>

    /** The `from` of every entry whose regex would not compile. */
    val invalid: List<String>

    init {
        val ok = mutableListOf<Pair<Pattern, WordFix>>()
        val bad = mutableListOf<String>()
        for (fix in fixes) {
            if (fix.from.isBlank()) continue
            try {
                ok += Pattern.compile(patternSource(fix), FLAGS) to fix
            } catch (e: PatternSyntaxException) {
                bad += fix.from
            }
        }
        compiled = ok
        invalid = bad
    }

    fun apply(text: String): String {
        var out = text
        for ((pattern, fix) in compiled) out = replaceAll(out, pattern.matcher(out), fix)
        return out
    }

    private fun replaceAll(text: String, matcher: Matcher, fix: WordFix): String {
        if (!matcher.find()) return text
        val out = StringBuilder()
        var last = 0
        do {
            out.append(text, last, matcher.start())
            out.append(replacement(matcher.group(), matcher.group(ENDING_GROUP), fix))
            last = matcher.end()
        } while (matcher.find())
        out.append(text, last, text.length)
        return out.toString()
    }

    companion object {
        private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

        // Named instead of numbered so a capture group inside `from` cannot steal group 1.
        private const val ENDING_GROUP = "cawEnding"

        fun patternSource(fix: WordFix): String {
            val core = "(?:${fix.from})(?<$ENDING_GROUP>${fix.endings})?"
            return if (fix.wholeWord) "(?<![A-Za-z])$core(?![A-Za-z])" else core
        }

        /** [heard] is the whole match including the ending; only its first character is tested. */
        fun replacement(heard: String, ending: String?, fix: WordFix): String {
            val word = when (fix.capitalization) {
                Capitalization.ALWAYS -> upperFirst(fix.to)
                Capitalization.MATCH_HEARD ->
                    if (heard.isNotEmpty() && heard[0].isUpperCase()) upperFirst(fix.to) else fix.to
                Capitalization.EXACT -> fix.to
            }
            return word + (ending ?: "").lowercase()
        }

        fun upperFirst(s: String): String =
            if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
    }
}
