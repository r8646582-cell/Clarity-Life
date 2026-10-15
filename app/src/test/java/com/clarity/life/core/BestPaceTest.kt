package com.clarity.life.core

import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [Planner.bestPace] reads only the check-ins inside each window since Max plan Phase 5. This compares it with
 * the plain way (every window over every check-in, as tools/planner.py does it) on random histories: unsorted,
 * with gaps, rest days, fractional amounts and a start day, so the faster way can never give another number.
 */
class BestPaceTest {

    /** The plain way: each window ending before today, over all the check-ins, through [Planner.typicalPace]. */
    private fun plainBest(all: List<CheckIn>, schedule: String, today: LocalDate, rest: Set<LocalDate>, start: LocalDate?): Double? {
        val checkins = if (start == null) all else all.filter { !it.day.isBefore(start) }
        val days = checkins.map { it.day }.filter { it.isBefore(today) }
        if (days.isEmpty()) return null
        val lastEnd = today.minusDays(1)
        var end = minOf(days.min().plusDays((Planner.WINDOW - 1).toLong()), lastEnd)
        var best: Double? = null
        while (!end.isAfter(lastEnd)) {
            // typicalPace's window is the WINDOW days before its "today", which is end + 1 here.
            val p = Planner.typicalPace(checkins, schedule, end.plusDays(1), rest).pace
            if (p != null && (best == null || p > best)) best = p
            end = end.plusDays(1)
        }
        return best
    }

    private fun history(r: Random, today: LocalDate, days: Int): List<CheckIn> {
        val levels = listOf("rest", "not", "minimum", "target", "beyond")
        val out = ArrayList<CheckIn>()
        for (i in 1..days) {
            if (r.nextDouble() < 0.3) continue // no answer that day
            val level = levels[r.nextInt(levels.size)]
            val amount = if (level == "not" || level == "rest") 0.0 else (r.nextInt(1, 400) / 7.0)
            out += CheckIn(today.minusDays(i.toLong()), level, amount)
        }
        out.shuffle(r)
        return out
    }

    @Test
    fun sameAsThePlainWayOnRandomHistories() {
        val r = Random(5)
        val today = LocalDate.parse("2026-10-14")
        val schedules = listOf("daily", "weekdays", "Mon,Wed,Fri", "Sat-Thu", "3x/week")
        repeat(60) { n ->
            val checkins = history(r, today, r.nextInt(0, 200))
            val schedule = schedules[n % schedules.size]
            val rest = (1..r.nextInt(0, 10)).map { today.minusDays(r.nextLong(1, 200)) }.toSet()
            val start = if (n % 3 == 0) today.minusDays(r.nextLong(0, 150)) else null
            assertEquals(
                "history $n ($schedule)",
                plainBest(checkins, schedule, today, rest, start),
                Planner.bestPace(checkins, schedule, today, rest, start),
            )
        }
    }

    @Test
    fun tenYearsOfDailyAnswersGiveTheSameBest() {
        val r = Random(11)
        val today = LocalDate.parse("2026-10-14")
        val checkins = history(r, today, 3650)
        assertEquals(
            plainBest(checkins, "daily", today, emptySet(), null),
            Planner.bestPace(checkins, "daily", today, emptySet(), null),
        )
    }
}
