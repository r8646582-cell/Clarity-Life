package com.clarity.life.core

/**
 * How hard the model thinks before it answers. The ids are DeepSeek's own reasoning_effort values, plus
 * "off" for thinking disabled (DeepSeek thinking mode guide, checked 10 Oct 2026: low, high and max; there is
 * no medium). Pure, so the label and the parsing are tested on the JVM.
 */
object Thinking {
    const val OFF = "off"
    const val LOW = "low"
    const val HIGH = "high"
    const val MAX = "max"

    /** In the order Settings shows them, with the words it shows. */
    val LEVELS = listOf(
        OFF to "Off",
        LOW to "Low",
        HIGH to "High",
        MAX to "Max",
    )

    /** A stored value read back; anything unknown falls back to [fallback], never to a guess. */
    fun parse(value: String?, fallback: String): String =
        value?.trim()?.lowercase()?.takeIf { v -> LEVELS.any { it.first == v } } ?: fallback

    /** What a coach message records as its model: "deepseek-flash, thinking low". Shown in Details. */
    fun label(model: String, level: String): String = "$model, thinking $level"
}
