package com.caw.android.remote

import java.util.Locale

/**
 * The computer's settings (protocol v3, docs/remote-protocol.md "v3: settings"), as its `settings`
 * reply carries them. They live on the computer, so they follow whichever computer is paired.
 */
data class DesktopSettings(
    val desktopName: String,
    val desktopVersion: String?,
    /** Heard phrase (lowercase, as the desktop stores it) -> what to write. */
    val wordFixes: Map<String, String>,
    val pointerSpeed: Double,
    val pointerAccel: Double,
)

/** A `settings_set`: only the fields that are not null are sent. [wordFixes] replaces the whole list. */
data class SettingsChange(
    val wordFixes: Map<String, String>? = null,
    val pointerSpeed: Double? = null,
    val pointerAccel: Double? = null,
) {
    val isEmpty: Boolean get() = wordFixes == null && pointerSpeed == null && pointerAccel == null
}

/** The desktop's limits for settings (v3). The phone checks them too, to say what is wrong before sending. */
object SettingsLimits {
    const val SPEED_MIN = 0.1
    const val SPEED_MAX = 10.0
    const val SPEED_DEFAULT = 1.5
    const val ACCEL_MIN = 0.0
    const val ACCEL_MAX = 1.0
    const val ACCEL_DEFAULT = 0.05
    const val MAX_FIXES = 500
    const val MAX_CHARS = 100

    /** How the desktop stores a heard phrase: trimmed, inner spaces collapsed, lowercase. */
    fun phrase(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Null if the desktop should accept [change]; otherwise what is wrong, in words. */
    fun problem(change: SettingsChange): String? {
        change.pointerSpeed?.let {
            if (it.isNaN() || it < SPEED_MIN || it > SPEED_MAX) return "Pointer speed must be between 0.1 and 10."
        }
        change.pointerAccel?.let {
            if (it.isNaN() || it < ACCEL_MIN || it > ACCEL_MAX) return "Pointer acceleration must be between 0 and 1."
        }
        change.wordFixes?.let { fixes ->
            if (fixes.size > MAX_FIXES) return "The computer keeps at most $MAX_FIXES word fixes."
            // Only the desktop's limits here: entries the desktop already holds are sent back as they are.
            for ((heard, write) in fixes) {
                if (heard.length > MAX_CHARS || write.length > MAX_CHARS) {
                    return "Each word fix can be at most $MAX_CHARS characters on each side (\"$heard\")."
                }
            }
        }
        return null
    }

    /** Null if one fix typed on the phone is fine (both sides filled in, within the limits); otherwise what is wrong. */
    fun fixProblem(heard: String, write: String): String? = when {
        phrase(heard).isEmpty() -> "Type what it hears."
        phrase(heard).length > MAX_CHARS -> "\"When it hears\" can be at most $MAX_CHARS characters."
        write.trim().isEmpty() -> "Type what it should write."
        write.trim().length > MAX_CHARS -> "\"Write\" can be at most $MAX_CHARS characters."
        else -> null
    }
}
