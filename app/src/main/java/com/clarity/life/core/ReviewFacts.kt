package com.clarity.life.core

import java.time.LocalDate

/**
 * The facts of a weekly or monthly review (spec section 8), counted here and never by the model: per target the
 * answers, done days, Not and Part days and the reasons behind them, per backlog its PLAN_STATE lines as of the
 * end of the period, the planned rest days, and the re-plan flag. The lines are what the review screen shows and
 * what the model gets as FACTS, so every number the note may use is one of these.
 *
 * Stored as review.facts_json, which is derived: it can always be rebuilt from the check-ins.
 * Pure Kotlin.
 */
object ReviewFacts {

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December",
    )
    private val PLURAL_DAYS = listOf("Mondays", "Tuesdays", "Wednesdays", "Thursdays", "Fridays", "Saturdays", "Sundays")

    /**
     * The same facts as [Planner.weeklyFacts], for any period [first]..[last] (a week or a month).
     * PlanFactsTest checks it gives exactly the weekly vectors' answers for a Monday-to-Sunday week.
     */
    fun period(
        checkins: List<CheckIn>,
        schedule: String,
        first: LocalDate,
        last: LocalDate,
        rest: Set<LocalDate> = emptySet(),
    ): WeeklyFacts {
        val parsed = Planner.parseSchedule(schedule)
        val sched = if (parsed is Schedule.Days) Planner.scheduledDates(schedule, first, last, rest).toSet() else null
        val rows = checkins.filter {
            !it.day.isBefore(first) && !it.day.isAfter(last) && it.level != "rest" && (sched == null || it.day in sched)
        }
        val done = rows.count { it.level in Planner.DONE_LEVELS }
        val missed = rows.filter { it.level == "not" }
        val short = rows.filter { it.level in Planner.SHORT_LEVELS }
        val minimum = rows.count { it.level == "minimum" }
        return WeeklyFacts(
            scheduled = sched?.size,
            answered = rows.size,
            done = done,
            missed = missed.size,
            part = minimum,
            doneRate = if (rows.isNotEmpty()) Planner.roundHalfUp(100.0 * done / rows.size) else null,
            answerRate = sched?.size?.takeIf { it > 0 }?.let { Planner.roundHalfUp(100.0 * rows.size / it) },
            minimum = minimum,
            target = rows.count { it.level == "target" || it.level == "beyond" },
            topReasons = short.mapNotNull { it.reason }.groupingBy { it }.eachCount().entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(3).map { it.key to it.value },
            missedByWeekday = missed.map { Planner.DAYS[it.day.dayOfWeek.value - 1] }.groupingBy { it }.eachCount().toSortedMap(),
        )
    }

    /** One backlog's figures at a review, as the re-plan trigger reads two reviews in a row. */
    data class BacklogPace(val id: Long, val projected: Double?, val remaining: Double, val answered: Int)

    data class Facts(
        val kind: String,
        val first: LocalDate,
        val last: LocalDate,
        val lines: List<String>,
        val backlogs: List<BacklogPace>,
        /** Answered scheduled days across every target; zero means there is nothing for a note to say. */
        val answered: Int,
        val replan: Boolean,
    ) {
        val text: String get() = lines.joinToString("\n")
    }

    fun title(kind: String, first: LocalDate, last: LocalDate): String = if (kind == "week") {
        "Week of ${TimeFacts.shortDay(first)} to ${TimeFacts.shortDay(last)}."
    } else {
        "${MONTHS[first.monthValue - 1]} ${first.year}, ${TimeFacts.shortDay(first)} to ${TimeFacts.shortDay(last)}."
    }

    /**
     * [targets] are the targets that ran during the period (with their whole history), [backlogs] the active
     * ones, [previous] the backlog figures of the last weekly review (for the re-plan flag; weeks only).
     */
    fun build(
        kind: String,
        first: LocalDate,
        last: LocalDate,
        targets: List<PlanBlocks.Target>,
        backlogs: List<PlanBlocks.Backlog>,
        rest: Set<LocalDate>,
        previous: List<BacklogPace> = emptyList(),
        availability: String = "not set",
    ): Facts {
        require(kind == "week" || kind == "month") { "a review is a week or a month" }
        val lines = mutableListOf(title(kind, first, last))
        var answered = 0
        if (targets.isEmpty()) lines += "Nothing was tracked."
        for (t in targets) {
            val f = period(t.checkins, t.schedule, first, last, rest)
            answered += f.answered
            val head = "T${t.id} \"${PlanBlocks.clean(t.name)}\""
            if (f.answered == 0) {
                lines += if (f.scheduled != null) "$head: no answers on its ${f.scheduled} scheduled days." else "$head: no answers."
                continue
            }
            val of = if (f.scheduled != null) "answered ${f.answered} of ${f.scheduled} scheduled days" else "answered ${f.answered} days (${t.schedule})"
            val part = if (f.minimum > 0) " (${f.minimum} of them at the minimum)" else ""
            val not = if (f.missed > 0) ", Not on ${f.missed}" else ""
            lines += "$head: $of, done on ${f.done}$part$not."
            val rates = listOfNotNull(
                f.doneRate?.let { "Done rate ${it.toInt()}%." },
                f.answerRate?.let { "Answer rate ${it.toInt()}%." },
            )
            if (rates.isNotEmpty()) lines += "  " + rates.joinToString(" ")
            Planner.reasonLines(f.topReasons, f.missed + f.part).forEach { lines += "  $it." }
            f.missedByWeekday.filter { it.value >= Planner.REASON_MIN_N }.forEach { (day, n) ->
                lines += "  Not on $n ${PLURAL_DAYS[Planner.DAYS.indexOf(day)]}."
            }
        }
        val restDays = rest.count { !it.isBefore(first) && !it.isAfter(last) }
        if (restDays > 0) lines += "Planned rest days: $restDays."

        // Backlogs as PLAN_STATE showed them the morning after the period ended.
        val asOf = last.plusDays(1)
        val paces = mutableListOf<BacklogPace>()
        for (b in backlogs) {
            val linked = targets.filter { it.backlogId == b.id }
            val bf = PlanBlocks.backlogFacts(b, linked, asOf, rest)
            lines += PlanStateText.backlogLines(
                "B${b.id}", PlanBlocks.clean(b.name), b.total, bf.done, bf.schedule, bf.projection, bf.windowAnswered,
                bf.answerRate, availability,
            )
            paces += BacklogPace(b.id, bf.projection.reachesTypical, bf.projection.remaining, bf.windowAnswered)
        }
        val replan = kind == "week" && paces.any { now ->
            val before = previous.firstOrNull { it.id == now.id } ?: return@any false
            if (now.projected == null || before.projected == null) return@any false
            Planner.replanFlag(
                listOf(
                    ReviewPace(before.projected, before.remaining, before.answered),
                    ReviewPace(now.projected, now.remaining, now.answered),
                ),
            )
        }
        if (replan) lines += "Re-plan flag: yes (below pace in two reviews in a row)."
        if (answered == 0) lines += "No check-ins were answered in this period, so there is too little data for a note."
        return Facts(kind, first, last, lines, paces, answered, replan)
    }

    // ---------- review.facts_json ----------

    fun toJson(f: Facts): String = StrictJson.write(
        Jv.Obj(
            linkedMapOf(
                "kind" to Jv.Str(f.kind),
                "first" to Jv.Str(f.first.toString()),
                "last" to Jv.Str(f.last.toString()),
                "answered" to Jv.Num(f.answered.toDouble(), true),
                "replan" to Jv.Bool(f.replan),
                "lines" to Jv.Arr(f.lines.map { Jv.Str(it) }),
                "backlogs" to Jv.Arr(
                    f.backlogs.map { b ->
                        Jv.Obj(
                            linkedMapOf(
                                "id" to Jv.Num(b.id.toDouble(), true),
                                "projected" to (b.projected?.let { Jv.Num(it, false) } ?: Jv.Null),
                                "remaining" to Jv.Num(b.remaining, false),
                                "answered" to Jv.Num(b.answered.toDouble(), true),
                            ),
                        )
                    },
                ),
            ),
        ),
    )

    /** Null when the stored JSON cannot be read (it is derived, so the review can simply be rebuilt). */
    fun fromJson(json: String): Facts? = try {
        val o = StrictJson.parse(json).asObj!!.map
        Facts(
            kind = o.getValue("kind").asStr!!,
            first = LocalDate.parse(o.getValue("first").asStr!!),
            last = LocalDate.parse(o.getValue("last").asStr!!),
            lines = o.getValue("lines").asList!!.map { it.asStr!! },
            backlogs = o.getValue("backlogs").asList!!.map { item ->
                val b = item.asObj!!.map
                BacklogPace(
                    b.getValue("id").asNum!!.toLong(),
                    b["projected"]?.asNum,
                    b.getValue("remaining").asNum!!,
                    b.getValue("answered").asNum!!.toInt(),
                )
            },
            answered = o.getValue("answered").asNum!!.toInt(),
            replan = o.getValue("replan").asBool!!,
        )
    } catch (e: Exception) {
        null
    }
}
