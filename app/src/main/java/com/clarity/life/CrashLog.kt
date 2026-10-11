package com.clarity.life

import android.content.Context
import java.io.File

/**
 * The last crash, kept on the phone so Settings can show it. It records only the kinds of error and the
 * lines of code they came from, never an error message (a message can quote what he wrote) and never a key.
 * Nothing is sent anywhere; this is what he photographs and shows when the app stops.
 */
object CrashLog {

    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                file.writeText(describe(thread.name, error))
            } catch (_: Throwable) {
                // Writing the log must never hide the crash itself.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A caught error that still stopped something he asked for: kept the same way, the app keeps running. */
    fun record(context: Context, error: Throwable) {
        try {
            File(context.filesDir, FILE).writeText(describe(Thread.currentThread().name, error))
        } catch (_: Throwable) {
        }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    internal fun describe(threadName: String, error: Throwable): String = buildString {
        append("Thread: ").append(threadName).append('\n')
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 4) {
            append(if (depth == 0) "Error: " else "Caused by: ").append(cause.javaClass.name).append('\n')
            cause.stackTrace.take(if (depth == 0) 14 else 8).forEach { append("  at ").append(it).append('\n') }
            cause = cause.cause
            depth++
        }
    }
}
