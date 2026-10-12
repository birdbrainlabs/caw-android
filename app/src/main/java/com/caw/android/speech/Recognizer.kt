package com.caw.android.speech

/** A speech recognizer (streaming, or offline with pause segmenting). All calls come from one background thread. */
interface Recognizer {
    /** Begin a fresh utterance; forgets earlier text. */
    fun start()

    /** 16 kHz mono samples in -1..1. */
    fun feed(samples: FloatArray)

    /** Everything heard since [start], as it stands now. */
    fun partial(): String

    /** No more audio: flush and return the final text. */
    fun finish(): String

    fun close()
}
