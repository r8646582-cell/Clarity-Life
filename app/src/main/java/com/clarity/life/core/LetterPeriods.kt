package com.clarity.life.core

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * The monthly and yearly letters (spec section 8, added 15 Oct 2026 at the owner's request), decided in code.
 *
 * - A monthly letter is written from that month's weekly letters: a week belongs to the month its Sunday is in.
 *   It is due from the 2nd of the next month (his day; [MONTH_GRACE_DAYS] day of grace), so the month's last
 *   Sunday letter has time to be written or retried first. It needs at least [MIN_WEEKLY] weekly letters.
 * - A yearly letter is written from that year's monthly letters. It is due from 3 January, once December's
 *   monthly letter is settled (written, skipped or given up), or from [YEAR_WAIT_UNTIL_DAY] January whatever
 *   happened. It needs at least [MIN_MONTHLY] monthly letters.
 * - Only the latest month and the latest year are written; a letter he deleted is never written again (its key).
 *
 * Pure Kotlin: [today] is his day (after the day boundary).
 */
object LetterPeriods {
    const val MIN_WEEKLY = 2
    const val MIN_MONTHLY = 2
    const val MONTH_GRACE_DAYS = 1
    const val YEAR_GRACE_DAYS = 2
    const val YEAR_WAIT_UNTIL_DAY = 10

    const val WEEK = "week"
    const val MONTH = "month"
    const val YEAR = "year"

    fun monthKey(first: LocalDate) = "letter:month:${first.toString().take(7)}"

    fun yearKey(year: Int) = "letter:year:$year"

    /** week, month or year, from a letter's key; a key from before monthly letters existed is a week. */
    fun kindOf(key: String): String = when {
        key.startsWith("letter:month:") -> MONTH
        key.startsWith("letter:year:") -> YEAR
        else -> WEEK
    }

    /** "weekly", "monthly" or "yearly", from a letter's key. */
    fun adjective(key: String): String = when (kindOf(key)) {
        MONTH -> "monthly"
        YEAR -> "yearly"
        else -> "weekly"
    }

    /** The first day of the month whose letter is due on [today], or null in the grace days after a month ends. */
    fun dueMonth(today: LocalDate): LocalDate? {
        if (today.dayOfMonth <= MONTH_GRACE_DAYS) return null
        return today.withDayOfMonth(1).minusMonths(1)
    }

    /** The year whose letter is due on [today], or null before it may be written. */
    fun dueYear(today: LocalDate, decemberSettled: Boolean): Int? {
        if (today.monthValue == 1) {
            if (today.dayOfMonth <= YEAR_GRACE_DAYS) return null
            if (!decemberSettled && today.dayOfMonth < YEAR_WAIT_UNTIL_DAY) return null
        }
        return today.year - 1
    }

    /** The weeks of a month: the Mondays whose Sunday falls in it. */
    fun mondaysOf(first: LocalDate): List<LocalDate> {
        val last = first.plusMonths(1).minusDays(1)
        var sunday = first
        while (sunday.dayOfWeek != java.time.DayOfWeek.SUNDAY) sunday = sunday.plusDays(1)
        val out = mutableListOf<LocalDate>()
        while (!sunday.isAfter(last)) {
            out += sunday.minusDays(6)
            sunday = sunday.plusWeeks(1)
        }
        return out
    }

    fun worthMonth(weeklyLetters: Int) = weeklyLetters >= MIN_WEEKLY

    fun worthYear(monthlyLetters: Int) = monthlyLetters >= MIN_MONTHLY

    /** "October 2026", as the prompt and the screen show a month. */
    fun monthName(first: LocalDate): String =
        first.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + first.year

    /** One source letter in a monthly or yearly letter's input: its heading, title and body. */
    fun source(heading: String, title: String, body: String): String =
        "$heading: \"${ContextBuilder.escape(title)}\"\n${ContextBuilder.escape(body)}"
}
