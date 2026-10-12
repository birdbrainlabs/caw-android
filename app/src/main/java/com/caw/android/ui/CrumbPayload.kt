package com.caw.android.ui

import kotlin.math.roundToInt

/**
 * What Crumb is told. Pure Kotlin so the exact string can be tested.
 * [level] is kept to two decimals so tiny mic changes do not count as a change.
 */
data class CrumbPayload(
    val active: Boolean = false,
    val recording: Boolean = false,
    val processing: Boolean = false,
    val waiting: Boolean = false,
    val level: Float = 0f,
    val delivery: Int = 0,
    val gentle: Boolean = false,
) {
    /** Hundredths, 0..100. */
    private val levelHundredths: Int = (level.coerceIn(0f, 1f) * 100f).roundToInt()

    /** Keys sorted, JSON numbers, bare booleans: the same form the Mac app sends. */
    fun toJson(): String {
        val lvl = when (levelHundredths) {
            0 -> "0"
            100 -> "1"
            else -> "0." + levelHundredths.toString().padStart(2, '0').trimEnd('0')
        }
        return "{\"active\":$active,\"delivery\":$delivery,\"gentle\":$gentle,\"level\":$lvl," +
            "\"processing\":$processing,\"recording\":$recording,\"waiting\":$waiting}"
    }

    fun toScript(): String = "window.updateCrumb(${toJson()})"

    override fun equals(other: Any?): Boolean =
        other is CrumbPayload && toJson() == other.toJson()

    override fun hashCode(): Int = toJson().hashCode()
}
