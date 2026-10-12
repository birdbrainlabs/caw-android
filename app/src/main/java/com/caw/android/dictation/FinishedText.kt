package com.caw.android.dictation

import com.caw.android.text.TextPipeline

/**
 * Where one finished, cleaned transcription goes: the one implementation shared by the IME pill
 * and the dictation screen.
 *
 * While "Send to computer" is on (a computer is paired and the switch is on) the text goes to the
 * computer as one `text` message, spaced as for an empty field (no leading space, one trailing
 * space: Caw cannot see the computer's text, and that is what makes one hold follow another).
 * It waits in RemoteLink's queue if the computer is away.
 *
 * Locally: the IME leaves the phone's field alone while sending ([Local.ONLY_WHEN_NOT_SENDING]);
 * the dictation screen keeps every text in its transcript as well ([Local.ALWAYS]).
 * Pure Kotlin.
 */
class FinishedText(private val computer: Computer) {

    interface Computer {
        /** Paired and the switch is on. */
        val sending: Boolean

        /** Queues [text] (already spaced) for the computer. */
        fun send(text: String)
    }

    enum class Local { ALWAYS, ONLY_WHEN_NOT_SENDING }

    /** What happened to one text: [local] if it went to [dispatch]'s local sink, [computerText] if it was sent. */
    data class Routed(val local: Boolean, val computerText: String?)

    fun dispatch(cleaned: String, local: Local, toLocal: (String) -> Unit): Routed {
        val computerText = if (computer.sending) forComputer(cleaned).also { computer.send(it) } else null
        val keep = local == Local.ALWAYS || computerText == null
        if (keep) toLocal(cleaned)
        return Routed(keep, computerText)
    }

    companion object {
        /** The exact text of the `text` message: spaced as for an empty field. */
        fun forComputer(cleaned: String): String = TextPipeline.commitString(null, cleaned)
    }
}
