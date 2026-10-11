package com.clarity.life.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When reviews and letters are due (spec section 8): the weekly review on Sunday at 19:00 his local time, the
 * letter at 19:30, the monthly review once a month has ended; on every app start the ones missed are caught up.
 * Only periods that overlap his tracking are reviewed, so a new install does not get a stack of empty reviews.
 *
 * Pure Kotlin: [now] is his local date and time.
 */
object ReviewTimes {
    val REVIEW_AT: LocalTime = LocalTime.of(19, 0)
    val LETTER_AT: LocalTime = LocalTime.of(19, 30)

    /** How far back a weekly review is still caught up, and a monthly one. */
    const val WEEKS_BACK = 4
    const val MONTHS_BACK = 2

    fun monday(d: LocalDate): LocalDate = d.minusDays((d.dayOfWeek.value - 1).toLong())

    /** The Monday of the newest week whose review (or letter, with [at] = [LETTER_AT]) is due at [now]. */
    fun latestWeek(now: LocalDateTime, at: LocalTime = REVIEW_AT): LocalDate {
        val thisMonday = monday(now.toLocalDate())
        val sundayReached = now.dayOfWeek == DayOfWeek.SUNDAY && !now.toLocalTime().isBefore(at)
        return if (sundayReached) thisMonday else thisMonday.minusWeeks(1)
    }

    /** Mondays of the weeks due, oldest first, each overlapping the tracking that began on [firstTracked]. */
    fun dueWeeks(now: LocalDateTime, firstTracked: LocalDate?, at: LocalTime = REVIEW_AT, back: Int = WEEKS_BACK): List<LocalDate> {
        if (firstTracked == null) return emptyList()
        val latest = latestWeek(now, at)
        return (back - 1 downTo 0).map { latest.minusWeeks(it.toLong()) }
            .filter { !it.plusDays(6).isBefore(firstTracked) }
    }

    /**
     * First days of the months due, oldest first. A month is due once his last day of it has ended, which is
     * at the day boundary ([boundaryHour], 4 am by default), not at midnight: at 00:30 on the 1st he is
     * still on the last day of the month, and a review written then would miss that evening's check-ins.
     */
    fun dueMonths(
        now: LocalDateTime,
        firstTracked: LocalDate?,
        boundaryHour: Int = 4,
        back: Int = MONTHS_BACK,
    ): List<LocalDate> {
        if (firstTracked == null) return emptyList()
        val hisDay = now.minusHours(boundaryHour.toLong()).toLocalDate()
        val latest = hisDay.withDayOfMonth(1).minusMonths(1)
        return (back - 1 downTo 0).map { latest.minusMonths(it.toLong()) }
            .filter { !it.plusMonths(1).minusDays(1).isBefore(firstTracked) }
    }

    /** The next Sunday at [at] strictly after [now]: when the weekly work runs next. */
    fun nextSunday(now: LocalDateTime, at: LocalTime = REVIEW_AT): LocalDateTime {
        var d = now.toLocalDate()
        while (true) {
            val candidate = d.atTime(at)
            if (d.dayOfWeek == DayOfWeek.SUNDAY && candidate.isAfter(now)) return candidate
            d = d.plusDays(1)
        }
    }

    fun weekKey(monday: LocalDate) = "review:week:$monday"

    fun monthKey(first: LocalDate) = "review:month:$first"

    fun letterKey(monday: LocalDate) = "letter:week:$monday"
}
