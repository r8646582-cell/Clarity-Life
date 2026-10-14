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

    /**
     * The band of the day, as the NOW sentence names it. Late night lasts until the day boundary or 4 am,
     * whichever is later, so with a midnight boundary 12:40 am is still late night, never "morning".
     */
    fun band(hour: Int, boundaryHour: Int): String = when {
        hour < maxOf(boundaryHour, PromiseTimes.LATE_NIGHT_END) -> "late night"
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

    /**
     * How far [day] is from [today], both day keys, counted here so the coach never has to: "today",
     * "yesterday", "3 days ago", "tomorrow", "in 3 days". Every date the coach sees in the past carries it.
     */
    fun ago(day: LocalDate, today: LocalDate): String {
        val n = java.time.temporal.ChronoUnit.DAYS.between(day, today)
        return when {
            n == 0L -> "today"
            n == 1L -> "yesterday"
            n > 1L -> "$n days ago"
            n == -1L -> "tomorrow"
            else -> "in ${-n} days"
        }
    }

    /** Fri 9 Oct, 21:40: the stamp his stored messages carry in the history window. */
    fun stamp(at: Instant, tz: String): String {
        val t = local(at, tz)
        val date = t.toLocalDate()
        return "${shortWeekday(date)} ${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}, " +
            "%02d:%02d".format(t.hour, t.minute)
    }

    /**
     * When something in the past happened, with the day, the time and how long ago, all counted here:
     * "Wed 14 Oct, 9:10 am (today, 2 hours 10 minutes ago)" within the last 24 hours, else
     * "Mon 12 Oct, 9:10 pm (2 days ago)". "Today" and "yesterday" follow his day ([boundaryHour]), like NOW
     * and the history stamps. Without the time, a plan made at 9 am and a message sent at 11 am the same day
     * read as if either could come first. A moment still ahead says only its day ("tomorrow").
     */
    fun pastStamp(at: Instant, now: Instant, tz: String, boundaryHour: Int): String {
        val t = local(at, tz)
        val date = t.toLocalDate()
        val ago = ago(Planner.dayKey(at.toString(), tz, boundaryHour), Planner.dayKey(now.toString(), tz, boundaryHour))
        val gap = Duration.between(at, now)
        val tail = if (!gap.isNegative && gap < Duration.ofHours(24)) "$ago, ${spell(gap)} ago" else ago
        return "${shortWeekday(date)} ${date.dayOfMonth} ${SHORT_MONTHS[date.monthValue - 1]}, ${clock(t)} ($tail)"
    }

    /**
     * When a conversation took place, from its first to its last message: "Wed 14 Oct, 9:05 am to 9:40 am",
     * or "Tue 13 Oct, 10:30 pm to Wed 14 Oct, 12:40 am" when it ran past midnight.
     */
    fun span(start: Instant, end: Instant, tz: String): String {
        val a = local(start, tz)
        val b = local(end, tz)
        fun day(d: LocalDate) = "${shortWeekday(d)} ${d.dayOfMonth} ${SHORT_MONTHS[d.monthValue - 1]}"
        return if (a.toLocalDate() == b.toLocalDate()) {
            "${day(a.toLocalDate())}, ${clock(a)} to ${clock(b)}"
        } else {
            "${day(a.toLocalDate())}, ${clock(a)} to ${day(b.toLocalDate())}, ${clock(b)}"
        }
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
