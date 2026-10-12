package com.caw.android.text

/**
 * Splits text for a paced commit: one word per chunk, carrying its trailing spaces and any
 * punctuation that touches it. Leading spaces ride on the first chunk, and a lone punctuation
 * mark (" , ") joins the chunk before it. Joining the chunks always gives back [text].
 */
object CommitChunks {
    private val PIECE = Regex("""\s*\S+\s*|\s+""")

    fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        for (m in PIECE.findAll(text)) {
            val piece = m.value
            val bare = piece.isBlank() || piece.none { it.isLetterOrDigit() }
            if (bare && out.isNotEmpty()) {
                out[out.size - 1] = out.last() + piece
            } else {
                out.add(piece)
            }
        }
        return out
    }
}
