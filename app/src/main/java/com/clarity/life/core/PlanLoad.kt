package com.clarity.life.core

import java.time.LocalDate

/**
 * A day's load across every goal, against his hours (Max plan Phase 6, step 3): a 1:1 port of the load section
 * of tools/planner.py (session_minutes, parse_hours, hours_text, day_load, load_lines). PlannerTest runs the same
 * vectors through here.
 *
 * Several goals compete for the same hours. Code adds up, for each of the next [LOAD_DAYS] days, the minutes the
 * live targets scheduled that day take, from their own minutes or hours or his estimate, and sets them against
 * the minutes he says he has that weekday. The coach is told the sums; it never adds them up itself.
 *
 * Pure Kotlin.
 */
object PlanLoad {

    const val LOAD_DAYS = 7
    private val MINUTE_UNITS = setOf("min", "mins", "minute", "minutes")
    private val HOUR_UNITS = setOf("h", "hr", "hrs", "hour", "hours")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val HOURS_RE = Regex("\\d{1,4}(?:\\.\\d+)?")

    /** A target as the load reads it. */
    data class Item(
        val id: Long,
        val schedule: String,
        val target: Double,
        val start: LocalDate?,
        val end: LocalDate?,
        val versions: List<TargetVersion>,
        val unit: String,
        val minutes: Double?,
    )

    data class Day(val day: LocalDate, val rest: Boolean, val minutes: Double, val timed: List<Long>, val untimed: List<Long>)

    data class Weekly(val id: Long, val perWeek: Int, val minutes: Double?)

    data class Load(val days: List<Day>, val weekly: List<Weekly>)

    /** A name for the text: its label ('T4 "Reading"') and its schedule. */
    data class Name(val label: String, val schedule: String)

    /**
     * Minutes one session at the target level takes: the target itself when its unit is minutes or hours, else
     * his own estimate, else null (not known, never guessed).
     */
    fun sessionMinutes(unit: String?, target: Double, minutes: Double? = null): Double? {
        val u = (unit ?: "").trim().lowercase()
        if (u in MINUTE_UNITS) return target
        if (u in HOUR_UNITS) return target * 60
        return minutes?.takeIf { it != 0.0 }
    }

    /**
     * His hours as the app stores them: seven numbers of minutes, Monday to Sunday, comma separated, each 0 to
     * 1440. Anything else is null (not set); it is never read loosely.
     */
    fun parseHours(text: String?): List<Double>? {
        if (text == null) return null
        val parts = text.split(",").map { it.trim() }
        if (parts.size != 7) return null
        return parts.map { x ->
            if (!HOURS_RE.matches(x)) return null
            val v = x.toDouble()
            if (v > 1440) return null
            v
        }
    }

    /** The stored form of [hours]: what [parseHours] reads back. */
    fun storeHours(hours: List<Double>): String {
        require(hours.size == 7 && hours.all { it in 0.0..1440.0 }) { "hours are seven days of 0 to 1440 minutes" }
        return hours.joinToString(",") { PlanStateText.g(it) }
    }

    private fun minText(m: Double): String = if (m <= Planner.EPS) "none" else "${PlanStateText.g(Planner.roundHalfUp(m))} min"

    /** "Mon to Fri 120 min, Sat 240 min, Sun none": runs of days with the same minutes, Monday first. */
    fun hoursText(hours: List<Double>): String {
        val out = mutableListOf<String>()
        var i = 0
        while (i < 7) {
            var j = i
            while (j + 1 < 7 && kotlin.math.abs(hours[j + 1] - hours[i]) < Planner.EPS) j++
            val days = if (i == j) Planner.DAYS[i] else "${Planner.DAYS[i]} to ${Planner.DAYS[j]}"
            out += "$days ${minText(hours[i])}"
            i = j + 1
        }
        return out.joinToString(", ")
    }

    private fun versions(t: Item): List<TargetVersion> = t.versions.ifEmpty {
        listOf(TargetVersion(t.start ?: LocalDate.of(1, 1, 1), 0.0, t.target, t.schedule, "active"))
    }

    /**
     * For each of [days] days from [today]: whether it is a planned rest day, the minutes of the targets scheduled
     * then that have a session time, and the ids of those that have none. A target counts on a day within its
     * start and end when the version in force then is active and scheduled that day. Nx/week targets have no
     * fixed days: they are listed apart with their sessions and minutes a week.
     */
    fun dayLoad(items: List<Item>, today: LocalDate, rest: Set<LocalDate> = emptySet(), days: Int = LOAD_DAYS): Load {
        val weekly = mutableListOf<Weekly>()
        for (t in items) {
            val v = Planner.versionOn(versions(t), today) ?: versions(t)[0]
            val s = Planner.parseSchedule(v.schedule)
            val runs = (t.end == null || !t.end.isBefore(today)) && (t.start == null || !t.start.isAfter(today.plusDays((days - 1).toLong())))
            if (s is Schedule.Count && v.status == "active" && runs) {
                val m = sessionMinutes(t.unit, v.target, t.minutes)
                weekly += Weekly(t.id, s.perWeek, m?.let { it * s.perWeek })
            }
        }
        val out = (0 until days).map { i ->
            val day = today.plusDays(i.toLong())
            if (day in rest) return@map Day(day, true, 0.0, emptyList(), emptyList())
            var minutes = 0.0
            val timed = mutableListOf<Long>()
            val untimed = mutableListOf<Long>()
            for (t in items) {
                if ((t.start != null && t.start.isAfter(day)) || (t.end != null && t.end.isBefore(day))) continue
                val v = Planner.versionOn(versions(t), day)
                if (v == null || v.status != "active") continue
                val s = Planner.parseSchedule(v.schedule)
                if (s !is Schedule.Days || (day.dayOfWeek.value - 1) !in s.weekdays) continue
                val m = sessionMinutes(t.unit, v.target, t.minutes)
                if (m == null) {
                    untimed += t.id
                } else {
                    minutes += m
                    timed += t.id
                }
            }
            Day(day, false, minutes, timed, untimed)
        }
        return Load(out, weekly)
    }

    /** The load as the coach reads it; empty when no target has a session time and his hours are not set. */
    fun loadLines(load: Load, names: Map<Long, Name>, hours: List<Double>? = null): List<String> {
        val days = load.days
        val timedAny = days.any { it.timed.isNotEmpty() } || load.weekly.any { it.minutes != null }
        if (!timedAny && hours == null) return emptyList()
        val g = PlanStateText::g
        val parts = days.map { r ->
            val label = "${Planner.DAYS[r.day.dayOfWeek.value - 1]} ${r.day.dayOfMonth} ${MONTHS[r.day.monthValue - 1]}"
            when {
                r.rest -> "$label planned rest day"
                hours == null -> "$label ${g(Planner.roundHalfUp(r.minutes))} min"
                else -> {
                    val m = Planner.roundHalfUp(r.minutes)
                    val have = Planner.roundHalfUp(hours[r.day.dayOfWeek.value - 1])
                    val over = m - have
                    "$label ${g(m)} of ${g(have)} min" + if (over > Planner.EPS) ", ${g(over)} over" else ""
                }
            }
        }
        val head = if (hours != null) {
            "Time planned, next ${days.size} days (minutes from his targets and his own time estimates; his hours from Plan): "
        } else {
            "Time planned, next ${days.size} days (minutes from his targets and his own time estimates; his hours are not set): "
        }
        val lines = mutableListOf(head + parts.joinToString("; ") + ".")
        val untimed = days.flatMap { it.untimed }.distinct()
        if (untimed.isNotEmpty()) {
            lines += "Not in these minutes (no time estimate): " + untimed.joinToString(", ") { names.getValue(it).label } + "."
        }
        for (w in load.weekly) {
            val n = names.getValue(w.id)
            lines += if (w.minutes == null) {
                "${n.label} is ${n.schedule}, not on fixed days, and has no time estimate."
            } else {
                "${n.label} is ${n.schedule}, not on fixed days: ${g(Planner.roundHalfUp(w.minutes))} min a week, not in the days above."
            }
        }
        if (hours != null) {
            val planned = days.filter { !it.rest }.sumOf { it.minutes } +
                load.weekly.mapNotNull { it.minutes }.sumOf { it * days.size / 7.0 }
            val have = days.filter { !it.rest }.sumOf { hours[it.day.dayOfWeek.value - 1] }
            val tail = if (load.weekly.any { it.minutes != null }) ", Nx/week targets included" else ""
            lines += "Next ${days.size} days in all: ${g(Planner.roundHalfUp(planned))} of ${g(Planner.roundHalfUp(have))} min available$tail."
        }
        return lines
    }
}
