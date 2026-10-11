package com.clarity.life.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Resolves a promise card's "when" in code (contracts/context.md, "Resolving 'when' in a promise card"), so the
 * AI never computes a date and the app never guesses one.
 *
 * A 1:1 port of tools/promise_times.py; PromiseTimesTest runs every case in contracts/promise_time_vectors.json.
 * The rules and the small grammar are written out in that file. In short:
 * - [Resolution.ok]: the phrase names exactly one moment in the future, which the card shows before he taps.
 * - otherwise the card opens its time picker, with the likeliest reading preselected when there is one
 *   (between midnight and the day boundary "today", "tonight" and "tomorrow" are always asked, never assumed).
 *
 * Pure: no clock and no time zone inside; [resolve] takes his local date and time.
 */
object PromiseTimes {

    data class Resolution(val ok: Boolean, val date: LocalDate?, val time: LocalTime?) {
        /** The moment to save; only when [ok]. */
        fun at(tz: String): Instant? =
            if (ok && date != null && time != null) toInstant(date, time, tz) else null
    }

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
    private val FILLER = setOf("at", "on", "this", "the", "by")
    private val RELATIVE = mapOf("today" to 0, "tonight" to 0, "tomorrow" to 1, "tmrw" to 1, "tmr" to 1)
    private val PARTS = mapOf("morning" to "am", "afternoon" to "pm", "evening" to "pm", "night" to "pm")
    const val MAX_DAYS_AHEAD = 365L

    private val TIME_RE = Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm)?$""")
    private val TIME_NO_SUFFIX_RE = Regex("""^\d{1,2}(?::\d{2})?$""")
    private val DAY_NUM_RE = Regex("""^(\d{1,2})(?:st|nd|rd|th)?$""")
    private val YEAR_RE = Regex("""^(20\d\d)$""")

    private fun pick(d: LocalDate? = null, t: LocalTime? = null) = Resolution(false, d, t)

    private class Unreadable : Exception()

    private class Found {
        var relative: String? = null
        var weekday: Int? = null
        var next = false
        var dom: Int? = null
        var month: Int? = null
        var year: Int? = null
        var time: LocalTime? = null
        var bareHour: Int? = null
        var half: String? = null
    }

    private fun tokens(phrase: String): List<String> {
        val s = phrase.lowercase().replace(",", " ").replace("a.m.", "am").replace("p.m.", "pm")
        val raw = s.split(Regex("""\s+""")).filter { it.isNotEmpty() && it !in FILLER }
        val out = mutableListOf<String>()
        for (w in raw) {
            // "5 pm" and "5:30 pm" become one token, like "5pm"
            if ((w == "am" || w == "pm") && out.isNotEmpty() && TIME_NO_SUFFIX_RE.matches(out.last())) {
                out[out.size - 1] = out.last() + w
            } else {
                out.add(w)
            }
        }
        return out
    }

    private fun parse(phrase: String): Found {
        val toks = tokens(phrase)
        if (toks.isEmpty()) throw Unreadable()
        val f = Found()
        var daysNamed = 0
        fun <T> once(current: T?, value: T): T {
            if (current != null) throw Unreadable()
            return value
        }
        var i = 0
        while (i < toks.size) {
            val w = toks[i]
            val nxt = toks.getOrNull(i + 1)
            when {
                w in RELATIVE -> {
                    f.relative = once(f.relative, w)
                    daysNamed++
                    if (w == "tonight") f.half = once(f.half, "pm")
                }
                w == "next" && nxt != null && nxt in WEEKDAYS -> {
                    f.weekday = once(f.weekday, WEEKDAYS.getValue(nxt))
                    f.next = true
                    daysNamed++
                    i++
                }
                w in WEEKDAYS -> f.weekday = once(f.weekday, WEEKDAYS.getValue(w))
                w in MONTHS -> {
                    f.month = once(f.month, MONTHS.getValue(w))
                    // the day of the month sits next to the month name: "10 Oct" or "Oct 10"
                    val dm = nxt?.let { DAY_NUM_RE.find(it) }
                    if (dm != null && f.dom == null) {
                        f.dom = dm.groupValues[1].toInt()
                        i++
                    }
                    val y = toks.getOrNull(i + 1)?.let { YEAR_RE.find(it) }
                    if (y != null) {
                        f.year = once(f.year, y.groupValues[1].toInt())
                        i++
                    }
                    daysNamed++
                }
                DAY_NUM_RE.matches(w) && nxt != null && nxt in MONTHS ->
                    f.dom = once(f.dom, DAY_NUM_RE.find(w)!!.groupValues[1].toInt())
                w in PARTS -> f.half = once(f.half, PARTS.getValue(w))
                w == "noon" -> f.time = once(f.time, LocalTime.of(12, 0))
                TIME_RE.matches(w) -> {
                    val m = TIME_RE.find(w)!!
                    var h = m.groupValues[1].toInt()
                    val mm = m.groups[2]?.value
                    val ampm = m.groups[3]?.value
                    val minute = mm?.toInt() ?: 0
                    if (minute > 59) throw Unreadable()
                    when {
                        ampm != null -> {
                            if (h !in 1..12) throw Unreadable()
                            h = h % 12 + if (ampm == "pm") 12 else 0
                            f.time = once(f.time, LocalTime.of(h, minute))
                        }
                        mm != null || h >= 13 -> {
                            if (h > 23) throw Unreadable()
                            f.time = once(f.time, LocalTime.of(h, minute))
                        }
                        else -> f.bareHour = once(f.bareHour, h)
                    }
                }
                else -> throw Unreadable()
            }
            i++
        }
        if (f.time != null && f.bareHour != null) throw Unreadable()
        // a weekday before a date ("Sat 10 Oct") is one day, not two
        if (f.weekday != null && !f.next && f.month == null) daysNamed++
        if (daysNamed > 1) throw Unreadable()
        if ((f.dom == null) != (f.month == null)) throw Unreadable()
        return f
    }

    /** Resolve a card's "when" against his local [now]. See tools/promise_times.py for every rule. */
    fun resolve(phrase: String, now: LocalDateTime, boundaryHour: Int): Resolution {
        val n = now.truncatedTo(ChronoUnit.MINUTES)
        val cal = n.toLocalDate()
        val late = n.hour < boundaryHour
        val dayKey = if (late) cal.minusDays(1) else cal
        val f = try {
            parse(phrase)
        } catch (e: Unreadable) {
            return pick()
        }

        // ---- the time of day ----
        var sureTime = true
        var t = f.time
        val half = f.half
        if (t != null && half != null && (half == "pm") != (t.hour >= 12)) return pick()
        f.bareHour?.let { h ->
            when {
                half == "pm" && h in 1..11 -> t = LocalTime.of(h + 12, 0)
                half == "am" && h in 1..11 -> t = LocalTime.of(h, 0)
                h <= 23 -> {
                    t = LocalTime.of(h, 0)
                    sureTime = false
                }
                else -> return pick()
            }
        }

        // ---- the day ----
        var sureDay = true
        var d: LocalDate
        when {
            f.month != null -> {
                val year = f.year ?: cal.year
                d = dateOrNull(year, f.month!!, f.dom!!) ?: return pick()
                if (f.year == null && d < cal) d = dateOrNull(year + 1, f.month!!, f.dom!!) ?: return pick()
                if (f.weekday != null && d.dayOfWeek.value - 1 != f.weekday) return pick()
            }
            f.weekday != null -> {
                val today = cal.dayOfWeek.value - 1
                if (f.next) {
                    d = cal.plusDays((Math.floorMod(f.weekday!! - today - 1, 7) + 1).toLong())
                    sureDay = false
                } else {
                    d = cal.plusDays(Math.floorMod(f.weekday!! - today, 7).toLong())
                    val tt = t
                    if (tt != null && !LocalDateTime.of(d, tt).isAfter(n)) d = d.plusDays(7)
                }
            }
            f.relative != null || t != null -> {
                val offset = RELATIVE[f.relative] ?: 0
                if (late) {
                    // his "today" is still yesterday on the calendar; the likeliest meaning is the coming day
                    d = cal
                    sureDay = false
                } else {
                    d = dayKey.plusDays(offset.toLong())
                }
            }
            else -> return pick(null, t)
        }

        if (ChronoUnit.DAYS.between(cal, d) > MAX_DAYS_AHEAD || d < cal) return pick()
        val time = t ?: return pick(d, null)
        if (!LocalDateTime.of(d, time).isAfter(n)) return pick(null, time)
        if (!(sureDay && sureTime)) return pick(d, time)
        return Resolution(true, d, time)
    }

    /** Resolve against a moment and his time zone, the way the app calls it. */
    fun resolve(phrase: String, now: Instant, tz: String, boundaryHour: Int): Resolution =
        resolve(phrase, TimeFacts.local(now, tz), boundaryHour)

    /** A local date and time to a moment. A time skipped by a clock change moves forward, never back. */
    fun toInstant(date: LocalDate, time: LocalTime, tz: String): Instant =
        LocalDateTime.of(date, time).atZone(ZoneId.of(tz)).toInstant()

    private fun dateOrNull(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (e: DateTimeException) {
        null
    }
}
