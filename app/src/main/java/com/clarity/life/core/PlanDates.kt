package com.clarity.life.core

import java.time.LocalDate

/**
 * PlanDates: the dates on a plan, backlog or target_change card, read in code. A 1:1 port of
 * tools/plan_dates.py; PlanDatesTest runs every case in contracts/plan_date_vectors.json.
 *
 * The model copies dates in his wording ("tomorrow", "Sat", "20 Nov", "2026-11-20"). The AI never computes a
 * date, so this reads them and NEVER GUESSES: a phrase it cannot read exactly is null, and a card with such a
 * date saves nothing (a plan card has no picker). The card shows every date it read before he taps.
 *
 * Pure Kotlin: no clock. [today] is his day key (the day boundary is already applied by the caller).
 */
object PlanDates {

    private val WEEKDAYS = mapOf(
        "monday" to 0, "mon" to 0, "tuesday" to 1, "tue" to 1, "tues" to 1, "wednesday" to 2, "wed" to 2,
        "thursday" to 3, "thu" to 3, "thur" to 3, "thurs" to 3, "friday" to 4, "fri" to 4, "saturday" to 5,
        "sat" to 5, "sunday" to 6, "sun" to 6,
    )
    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4,
        "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8,
        "september" to 9, "sep" to 9, "sept" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11,
        "december" to 12, "dec" to 12,
    )
    private val FILLER = setOf("on", "the", "by", "from", "until")
    private val RELATIVE = mapOf("today" to 0L, "tomorrow" to 1L, "tmrw" to 1L, "tmr" to 1L)
    const val MAX_YEARS = 5

    /** Review options are one or two weeks out, moved to a scheduled day. */
    const val REVIEW_MAX_DAYS = 28L

    private val ISO_RE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val DAY_NUM_RE = Regex("""^(\d{1,2})(?:st|nd|rd|th)?$""")
    private val YEAR_RE = Regex("""^(20\d\d)$""")

    private class Unreadable : Exception()

    private fun make(y: Int, m: Int, d: Int): LocalDate = try {
        LocalDate.of(y, m, d)
    } catch (e: java.time.DateTimeException) {
        throw Unreadable()
    }

    /** Mon=0 ... Sun=6, like the Python reference. */
    private fun weekday(d: LocalDate) = d.dayOfWeek.value - 1

    /** The date a phrase names, or null when it cannot be read exactly. */
    fun readDay(phrase: String?, today: LocalDate): LocalDate? = try {
        read(phrase, today)
    } catch (e: Unreadable) {
        null
    }

    private fun read(phrase: String?, today: LocalDate): LocalDate {
        if (phrase == null) throw Unreadable()
        var toks = phrase.lowercase().replace(",", " ").split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it !in FILLER }
        if (toks.isEmpty()) throw Unreadable()
        if (toks.size == 1 && toks[0] in RELATIVE) return today.plusDays(RELATIVE.getValue(toks[0]))
        if (toks.size == 1) {
            ISO_RE.matchEntire(toks[0])?.let { m ->
                val (y, mo, d) = m.destructured
                return check(make(y.toInt(), mo.toInt(), d.toInt()), today)
            }
        }
        var wd: Int? = null
        if (toks[0] in WEEKDAYS) {
            wd = WEEKDAYS.getValue(toks[0])
            toks = toks.drop(1)
            if (toks.isEmpty()) return today.plusDays(((wd - weekday(today)) % 7 + 7) % 7L)
        }
        var day: Int? = null
        var month: Int? = null
        var year: Int? = null
        for (w in toks) {
            val dm = DAY_NUM_RE.matchEntire(w)
            when {
                w in MONTHS && month == null -> month = MONTHS.getValue(w)
                dm != null && day == null -> day = dm.groupValues[1].toInt()
                YEAR_RE.matches(w) && year == null && day != null && month != null -> year = w.toInt()
                else -> throw Unreadable()
            }
        }
        if (day == null || month == null) throw Unreadable()
        val d: LocalDate = if (year != null) {
            make(year, month, day)
        } else {
            listOf(today.year, today.year + 1).firstNotNullOfOrNull { y ->
                try {
                    LocalDate.of(y, month, day).takeIf { !it.isBefore(today) }
                } catch (e: java.time.DateTimeException) {
                    null
                }
            } ?: throw Unreadable()
        }
        if (wd != null && weekday(d) != wd) throw Unreadable()
        return check(d, today)
    }

    private fun check(d: LocalDate, today: LocalDate): LocalDate {
        if (d.isBefore(today)) throw Unreadable()
        if (d.isAfter(make(today.year + MAX_YEARS, today.monthValue, minOf(today.dayOfMonth, 28)))) throw Unreadable()
        return d
    }

    /** The dates of one card, read in code. Only the fields that belong to the card's type are set. */
    data class CardDays(
        val targets: List<Pair<LocalDate, LocalDate?>> = emptyList(),
        val deadlines: List<LocalDate> = emptyList(),
        val review: LocalDate? = null,
        val end: LocalDate? = null,
        val deadline: LocalDate? = null,
    )

    sealed interface Checked {
        data class Ok(val days: CardDays) : Checked
        data class Bad(val why: String) : Checked
    }

    private class BadCard(why: String) : Exception(why)

    /** [type] and [fields] as the card parser keeps them (or a raw card object, as the vectors give it). */
    fun checkCard(type: String, fields: Map<String, Jv>, today: LocalDate): Checked {
        fun need(phrase: Jv?, what: String): LocalDate =
            readDay(phrase?.asStr, today) ?: throw BadCard("unreadable $what")
        return try {
            when (type) {
                "plan" -> {
                    // A ramp: a target with "weeks" lasts that many weeks, and the next target with no start begins
                    // the day after it ends. The dates are worked out here, never by the coach.
                    var stepEnd: LocalDate? = null
                    val targets = (fields["targets"]?.asList ?: emptyList()).map { item ->
                        val t = item.asObj?.map ?: throw BadCard("target item")
                        val weeks = t["weeks"]?.asNum?.toLong()
                        val start = when {
                            "start" in t -> need(t["start"], "start")
                            stepEnd != null -> stepEnd!!.plusDays(1)
                            else -> today
                        }
                        val end = when {
                            "end" in t -> need(t["end"], "end")
                            weeks != null -> start.plusDays(7 * weeks - 1)
                            else -> null
                        }
                        if (end != null && end.isBefore(start)) throw BadCard("end before start")
                        stepEnd = if (weeks != null) end else null
                        start to end
                    }
                    val deadlines = (fields["backlog"]?.asList ?: emptyList()).map { item ->
                        need(item.asObj?.map?.get("deadline"), "deadline")
                    }
                    val review = need(fields["review_date"], "review_date")
                    if (!review.isAfter(today)) throw BadCard("review not after today")
                    if (review.isAfter(today.plusDays(REVIEW_MAX_DAYS))) throw BadCard("review too far ahead")
                    Checked.Ok(CardDays(targets = targets, deadlines = deadlines, review = review))
                }
                "target_change" -> Checked.Ok(CardDays(end = if ("end" in fields) need(fields["end"], "end") else null))
                "backlog" -> Checked.Ok(
                    CardDays(deadline = if ("deadline" in fields) need(fields["deadline"], "deadline") else null),
                )
                else -> throw BadCard("no dates on this card")
            }
        } catch (e: BadCard) {
            Checked.Bad(e.message ?: "bad")
        }
    }

    fun checkCard(card: Card, today: LocalDate): Checked = checkCard(card.type, card.fields, today)
}
