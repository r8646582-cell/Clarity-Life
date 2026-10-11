package com.clarity.life.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * The time sentences the coach reads, all written by code (rule 2: the AI never works out a date).
 *
 * - [nowSentence] is the NOW block of contracts/context.md, word for word, including the note that after
 *   midnight their day still counts as the day before until the boundary hour.
 * - [duePhrase] is the wording an open promise carries in OPEN_PROMISES.
 * - [stamp] is the time stamp his own messages carry when they are sent back in the history window.
 *
 * English names come from fixed tables, never from the phone's locale, so a language change cannot alter
 * what the model is told. Adapted from the old repo's time/TimeFacts.kt.
 */
object TimeFacts {

    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val SHORT_WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )
    private val SHORT_MONTHS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    fun local(at: Instant, tz: String): LocalDateTime = at.atZone(ZoneId.of(tz)).toLocalDateTime()

    private fun weekday(d: LocalDate) = WEEKDAYS[d.dayOfWeek.value - 1]

    private fun shortWeekday(d: LocalDate) = SHORT_WEEKDAYS[d.dayOfWeek.value - 1]

    /** 9:40 pm, 12:40 am, 8:00 am: the way he would say it. */
    fun clock(t: LocalDateTime): String {
        val hour = when (t.hour % 12) {
            0 -> 12
            else -> t.hour % 12
        }
        val suffix = if (t.hour < 12) "am" else "pm"
        return "%d:%02d %s".format(hour, t.minute, suffix)
    }

    /** The band of the day, as the NOW sentence names it. */
    fun band(hour: Int, boundaryHour: Int): String = when {
        hour < boundaryHour -> "late night"
        hour < 12 -> "morning"
        hour < 17 -> "afternoon"
        hour < 21 -> "evening"
        else -> "night"
    }

    /**
     * The NOW block: one sentence naming the moment and, before the day boundary, the day it still counts as.
     *
     * `It's Friday 9 October 2026, 9:40 pm (night).`
     * `It's Sunday 4 October 2026, 12:40 am (late night; their day still counts as Saturday until 4 am).`
     */
    fun nowSentence(at: Instant, tz: String, boundaryHour: Int): String {
        val t = local(at, tz)
        val date = t.toLocalDate()
        val head = "It's ${weekday(date)} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]} ${date.year}, " +
            clock(t)
        val band = band(t.hour, boundaryHour)
        val tail = if (t.hour < boundaryHour) {
            val counts = date.minusDays(1)
            "$band; their day still counts as ${weekday(counts)} until $boundaryHour am"
        } else {
            band
        }
        return "$head ($tail)."
    }

    /** Sat 10 Oct, 3:00 pm: what a promise card shows him before he taps. */
    fun cardTime(at: Instant, tz: String): String {
        val t = local(at, tz)
        val date = t.toLocalDate()
        return "${shortWeekday(date)} ${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}, ${clock(t)}"
    }

    /** Sat 10 Oct: a day on a plan card or a Plan screen, always with its weekday so it cannot be misread. */
    fun shortDay(date: LocalDate): String =
        "${shortWeekday(date)} ${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}"

    /** Fri 9 Oct, 21:40: the stamp his stored messages carry in the history window. */
    fun stamp(at: Instant, tz: String): String {
        val t = local(at, tz)
        val date = t.toLocalDate()
        return "${shortWeekday(date)} ${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}, " +
            "%02d:%02d".format(t.hour, t.minute)
    }

    /**
     * The due phrase of an open promise, computed here so the coach never has to:
     * "due Monday 5 October, 8:00 am (in 1 day 7 hours)" or "was due Friday 5:00 pm, 2 days ago".
     */
    fun duePhrase(dueAt: Instant, now: Instant, tz: String): String {
        val due = local(dueAt, tz)
        val date = due.toLocalDate()
        val gap = Duration.between(now, dueAt)
        return if (!gap.isNegative) {
            "due ${weekday(date)} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}, ${clock(due)} " +
                "(in ${spell(gap)})"
        } else {
            "was due ${weekday(date)} ${clock(due)}, ${spell(gap)} ago"
        }
    }

    /** "1 day 7 hours", "2 days", "40 minutes": whole units only, never a decimal. */
    fun spell(gap: Duration): String {
        val seconds = abs(gap.seconds)
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            days > 0 && hours > 0 -> "${plural(days, "day")} ${plural(hours, "hour")}"
            days > 0 -> plural(days, "day")
            hours > 0 && minutes > 0 -> "${plural(hours, "hour")} ${plural(minutes, "minute")}"
            hours > 0 -> plural(hours, "hour")
            minutes > 0 -> plural(minutes, "minute")
            else -> "less than a minute"
        }
    }

    private fun plural(n: Long, unit: String) = "$n $unit" + if (n == 1L) "" else "s"

    /**
     * Between midnight and the day boundary, "today", "tonight" and "tomorrow" mean two different things,
     * so the app never guesses: the card opens its time picker instead (contracts/context.md).
     */
    fun isAmbiguousNow(at: Instant, tz: String, boundaryHour: Int): Boolean =
        local(at, tz).hour < boundaryHour
}
