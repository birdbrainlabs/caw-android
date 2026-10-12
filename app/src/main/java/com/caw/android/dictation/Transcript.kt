package com.caw.android.dictation

import com.caw.android.text.TextPipeline

/**
 * The dictation screen's text: every finished hold, appended with the IME's spacing rules
 * ([TextPipeline.commitString]: a leading space unless the text is empty or already ends in
 * whitespace, then one trailing space), so it reads exactly as if each hold had been typed into
 * one empty text box. Pure Kotlin; main thread only.
 */
class Transcript(initial: String = "") {

    var text: String = initial
        private set

    val isEmpty: Boolean get() = text.isBlank()

    /** Adds one finished (cleaned) hold. Blank text is ignored. */
    fun append(cleaned: String) {
        if (cleaned.isBlank()) return
        val before = text.takeLast(1).ifEmpty { null }
        text += TextPipeline.commitString(before, cleaned.trim())
    }

    /** Empties the transcript and returns what was there (for undo). */
    fun clear(): String {
        val old = text
        text = ""
        return old
    }

    /** Puts back [saved] (undo of [clear], or the text saved across rotation or process death). */
    fun restore(saved: String) {
        text = saved
    }

    /**
     * What Copy puts on the clipboard: the whole transcript exactly as shown, including the one
     * trailing space the IME would have typed, so pasting it and dictating more joins cleanly.
     */
    fun copyText(): String = text
}
