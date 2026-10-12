package com.caw.android.text

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

data class WordFixDocument(val version: Int, val about: String?, val fixes: List<WordFix>)

/** Reads and writes the word-fixes.json format (the same as the Mac app's). */
object WordFixJson {
    const val DEFAULT_ABOUT = "Word fixes: when the recognizer writes \"from\", your text says \"to\". " +
        "Applied in order. Edit them in Settings › Words & Learning."

    fun parse(text: String): WordFixDocument {
        val root = JSONObject(text)
        val array = root.getJSONArray("fixes")
        val fixes = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            WordFix(
                from = o.getString("from"),
                to = o.getString("to"),
                capitalization = Capitalization.parse(o.stringOrNull("capitalization")),
                wholeWord = o.optBoolean("wholeWord", true),
                endings = o.stringOrNull("endings") ?: WordFix.DEFAULT_ENDINGS,
                note = o.stringOrNull("note"),
            )
        }
        return WordFixDocument(root.optInt("version", 1), root.stringOrNull("about"), fixes)
    }

    fun write(doc: WordFixDocument): String {
        val root = JSONObject()
        root.put("version", doc.version)
        root.put("about", doc.about ?: DEFAULT_ABOUT)
        val array = JSONArray()
        for (fix in doc.fixes) {
            val o = JSONObject()
            o.put("from", fix.from)
            o.put("to", fix.to)
            o.put("capitalization", fix.capitalization.key)
            if (!fix.wholeWord) o.put("wholeWord", false)
            if (fix.endings != WordFix.DEFAULT_ENDINGS) o.put("endings", fix.endings)
            fix.note?.let { o.put("note", it) }
            array.put(o)
        }
        root.put("fixes", array)
        return root.toString(2) + "\n"
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}

/**
 * The user's word-fixes.json in the app's files dir. On first use the bundled seed is copied there
 * (never over an existing file). The file is re-checked at most every 0.5 s and reloaded when it changed.
 *
 * @param readSeed returns the bundled seed JSON (assets/word-fixes.json).
 * @param post runs listener callbacks on the main thread.
 */
class WordFixStore(
    private val dir: File,
    private val readSeed: () -> String,
    private val post: (Runnable) -> Unit = { it.run() },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val FILE_NAME = "word-fixes.json"
        private const val RECHECK_MS = 500L
    }

    val file = File(dir, FILE_NAME)

    /** Null when the list loaded cleanly; otherwise the message to show. */
    @Volatile
    var problem: String? = null
        private set

    private var loaded: WordFixes? = null
    private var document: WordFixDocument? = null
    private var loadedStamp = 0L
    private var lastCheck = 0L
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    @Synchronized
    fun current(): WordFixes {
        val now = clock()
        val cached = loaded
        if (cached != null && now - lastCheck < RECHECK_MS) return cached
        lastCheck = now
        ensureFile()
        val stamp = stampOf(file)
        if (cached != null && stamp == loadedStamp) return cached
        return reload(stamp)
    }

    @Synchronized
    fun save(fixes: List<WordFix>) {
        if (document == null) current()
        val old = document
        val doc = WordFixDocument(old?.version ?: 1, old?.about ?: WordFixJson.DEFAULT_ABOUT, fixes)
        dir.mkdirs()
        writeAtomically(WordFixJson.write(doc))
        loaded = null // force the reload
        current()
        for (listener in listeners) post(Runnable { listener() })
    }

    private fun ensureFile() {
        if (file.exists()) return
        dir.mkdirs()
        writeAtomically(readSeed())
    }

    private fun reload(stamp: Long): WordFixes {
        loadedStamp = stamp
        return try {
            val doc = WordFixJson.parse(file.readText())
            val fixes = WordFixes(doc.fixes)
            document = doc
            loaded = fixes
            problem = if (fixes.invalid.isEmpty()) {
                null
            } else {
                "Skipped ${fixes.invalid.size} fix(es) that could not be read: ${fixes.invalid.joinToString(", ")}"
            }
            fixes
        } catch (e: Exception) {
            val fallback = loaded ?: WordFixes(WordFixJson.parse(readSeed()).fixes)
            loaded = fallback
            problem = "word-fixes.json could not be read (${e.message}). Using the previous list."
            fallback
        }
    }

    private fun writeAtomically(text: String) {
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }

    // Modification time plus length, so an edit inside the same clock second still counts.
    private fun stampOf(f: File): Long = f.lastModified() * 31 + f.length()
}
