package com.clarity.life.core

/**
 * Chat commands, detected by code before a message is sent (docs/CLARITY-V2-BUILD-SPEC.md section 8). A 1:1 port of
 * tools/commands.py; ChatCommandsTest runs every case in contracts/command_vectors.json. The only command is
 * "forget that", and it only opens a confirm sheet: nothing is deleted without a tap.
 */
object ChatCommands {
    private val FORGET = setOf("forget that", "forget this", "forget that note", "forget this note")

    /** "forget" when the whole message is a forget phrase, otherwise null. */
    fun match(text: String?): String? {
        var t = (text ?: "").trim().lowercase().replace(Regex("\\s+"), " ")
        t = t.replace(Regex("[\\s.,!]+$"), "")
        return if (t in FORGET) "forget" else null
    }
}
