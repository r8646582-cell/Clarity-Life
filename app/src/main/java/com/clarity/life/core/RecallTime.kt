package com.clarity.life.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * The time and outcome words in a message, read in code, so search can look in the right part of their life
 * (docs/MAX-PLAN.md, Phase 2 step 2). A 1:1 port of tools/recall_time.py; RecallTimeTest runs every case of
 * contracts/recall_time_vectors.json. The rules are written there, one vector each.
 *
 * "Which promises did I break in August?" shares no word with the promises it asks about. Code reads the period
 * instead (1 Aug to 31 Aug, the most recent August that has begun), and search brings what falls in it. The AI
 * never works out a date (rule 2): this only chooses what is looked up. Pure, no clock.
 */
object RecallTime {

    /** [start] and [end] are null when the text names no period. [rest] is the text without the period's words. */
    data class Period(
        val start: LocalDate?,
        val end: LocalDate?,
        val promises: Boolean,
        val outcome: String?,
        val rest: String,
    )

    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4, "apr" to 4,
        "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8, "september" to 9,
        "sep" to 9, "sept" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )
    private val WEEKDAYS = mapOf(
        "monday" to 0, "tuesday" to 1, "wednesday" to 2, "thursday" to 3, "friday" to 4, "saturday" to 5, "sunday" to 6,
    )
    private val NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "couple of" to 2, "few" to 3,
    )
    private val ORDINAL_WEEK = mapOf(
        "first" to 1, "1st" to 1, "second" to 2, "2nd" to 2, "third" to 3, "3rd" to 3, "fourth" to 4, "4th" to 4, "last" to 5,
    )
    private const val MAX_COUNT = 400

    private const val M = "(?<m>january|february|march|april|may|june|july|august|september|october|november|december|" +
        "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)"
    private val M2 = M.replace("?<m>", "?<m2>")
    private const val W = "(?<w>monday|tuesday|wednesday|thursday|friday|saturday|sunday)"
    private const val N = "(?<n>\\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|couple of|few)"
    private const val Y = "(?<y>20\\d\\d)"
    private const val D = "(?<d>\\d{1,2})(?:st|nd|rd|th)?"
    private val D2 = D.replace("?<d>", "?<d2>")
    private const val TIME_WORD = "(?:in|during|since|of|last|this|early|mid|late|through|throughout|from|until|before|after)"

    /** In order: the first pattern that matches at the earliest position wins. */
    private val PATTERNS: List<Pair<String, Pattern>> = listOf(
        "week_of" to "\\b(?<o>first|1st|second|2nd|third|3rd|fourth|4th|last) week (?:of|in) $M(?: $Y)?\\b",
        "part_of" to "\\b(?<p>early|beginning of|start of|mid|middle of|late|end of) $M(?: $Y)?\\b",
        "day_month" to "\\b$D(?: of)? $M(?:,? $Y)?\\b",
        "month_day" to "\\b$M2 $D2(?:,? ${Y.replace("?<y>", "?<y2>")})?\\b",
        "since_month" to "\\bsince $M(?: $Y)?\\b",
        "last_month_name" to "\\blast $M\\b",
        "month" to "\\b$TIME_WORD (?:the month of )?$M(?: $Y)?\\b",
        "month_year" to "\\b$M $Y\\b",
        "today" to "\\b(?:earlier today|today|this morning|this afternoon|this evening|tonight)\\b",
        "yesterday" to "\\b(?:yesterday|last night)\\b",
        "tomorrow" to "\\btomorrow\\b",
        "days_ago" to "\\b$N days? ago\\b",
        "weeks_ago" to "\\b$N weeks? ago\\b",
        "months_ago" to "\\b$N months? ago\\b",
        "past_days" to "\\b(?:the )?(?:past|last) $N days\\b",
        "past_weeks" to "\\b(?:the )?(?:past|last) $N weeks\\b",
        "this_week" to "\\bthis week\\b",
        "last_week" to "\\b(?:last week|the week before)\\b",
        "this_month" to "\\bthis month\\b",
        "last_month" to "\\blast month\\b",
        "this_year" to "\\bthis year\\b",
        "last_year" to "\\blast year\\b",
        "year" to "\\b(?:in|during|since) $Y\\b",
        "weekday" to "\\b(?:(?:last|on|this past) $W|${W.replace("?<w>", "?<w2>")}'s)\\b",
    ).map { (name, rx) -> name to Pattern.compile(rx) }

    private val PROMISE = Regex("\\b(?:promise|promised|promises|commit|committed|commitment)\\b")
    private val OUTCOMES = listOf(
        "not_done" to Regex("\\b(?:break|broke|broken|miss|missed|fail|failed|skip|skipped)\\b"),
        "kept" to Regex("\\b(?:keep|kept|managed)\\b"),
        "moved" to Regex("\\b(?:moved|postponed|pushed)\\b"),
        "let_go" to Regex("\\b(?:let go|dropped|gave up)\\b"),
    )

    fun norm(text: String): String {
        val t = text.lowercase(Locale.ROOT).replace('’', '\'').replace('‘', '\'')
        return t.replace(Regex("[^a-z0-9' ]+"), " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun parse(text: String, today: LocalDate): Period {
        val t = norm(text)
        var found: Triple<Int, Int, Pair<LocalDate, LocalDate>>? = null
        for ((name, rx) in PATTERNS) {
            val m = rx.matcher(t)
            while (m.find()) {
                if (found != null && m.start() >= found.first) break
                val p = period(name, m, today) ?: continue
                found = Triple(m.start(), m.end(), p)
                break
            }
        }
        var start: LocalDate? = null
        var end: LocalDate? = null
        var rest = t
        if (found != null) {
            val (s, e) = found.third
            if (!s.isAfter(e) && !s.isAfter(today.plusDays(1))) {
                start = s
                end = e
                rest = (t.substring(0, found.first) + " " + t.substring(found.second))
                    .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
            }
        }
        val promises = PROMISE.containsMatchIn(t)
        val outcome = if (promises) OUTCOMES.firstOrNull { it.second.containsMatchIn(t) }?.first else null
        return Period(start, end, promises, outcome, rest)
    }

    private fun Matcher.opt(group: String): String? = try {
        group(group)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun number(word: String?): Int? {
        if (word == null) return null
        if (word.all(Char::isDigit)) return word.toInt().takeIf { it in 1..MAX_COUNT }
        return NUMBERS[word]
    }

    private fun monthEnd(y: Int, m: Int): LocalDate = YearMonth.of(y, m).atEndOfMonth()

    /** The most recent month [m] that has begun, or the one in [year]. */
    private fun recentMonth(m: Int, today: LocalDate, year: Int?, last: Boolean): Pair<Int, Int> {
        if (year != null) return year to m
        var y = if (m <= today.monthValue) today.year else today.year - 1
        if (last && m == today.monthValue) y -= 1
        return y to m
    }

    private fun shiftMonth(y: Int, m: Int, back: Int): Pair<Int, Int> {
        val k = y * 12 + (m - 1) - back
        return Math.floorDiv(k, 12) to Math.floorMod(k, 12) + 1
    }

    private fun period(name: String, g: Matcher, today: LocalDate): Pair<LocalDate, LocalDate>? {
        val year = g.opt("y")?.toInt()
        when (name) {
            "week_of", "part_of", "since_month", "last_month_name", "month", "month_year" -> {
                val (y, m) = recentMonth(MONTHS.getValue(g.group("m")), today, year, last = name == "last_month_name")
                val end = monthEnd(y, m)
                return when (name) {
                    "week_of" -> {
                        val k = ORDINAL_WEEK.getValue(g.group("o"))
                        if (k == 5) end.minusDays(6) to end else LocalDate.of(y, m, 7 * k - 6) to LocalDate.of(y, m, 7 * k)
                    }
                    "part_of" -> when (g.group("p")) {
                        "early", "beginning of", "start of" -> LocalDate.of(y, m, 1) to LocalDate.of(y, m, 10)
                        "mid", "middle of" -> LocalDate.of(y, m, 11) to LocalDate.of(y, m, 20)
                        else -> LocalDate.of(y, m, 21) to end
                    }
                    "since_month" -> LocalDate.of(y, m, 1) to today
                    else -> LocalDate.of(y, m, 1) to end
                }
            }
            "day_month", "month_day" -> {
                val mword = if (name == "day_month") g.group("m") else g.group("m2")
                val dnum = (if (name == "day_month") g.group("d") else g.group("d2")).toInt()
                val yword = if (name == "day_month") g.opt("y") else g.opt("y2")
                // "I may 2 hours" is not 2 May (DayCounts).
                if (name == "month_day" && mword == "may" && yword == null) return null
                val m = MONTHS.getValue(mword)
                return try {
                    val d = if (yword != null) {
                        LocalDate.of(yword.toInt(), m, dnum)
                    } else {
                        val thisYear = LocalDate.of(today.year, m, dnum)
                        if (thisYear.isAfter(today)) LocalDate.of(today.year - 1, m, dnum) else thisYear
                    }
                    d to d
                } catch (e: DateTimeException) {
                    null
                }
            }
            "today" -> return today to today
            "yesterday" -> return today.minusDays(1) to today.minusDays(1)
            "tomorrow" -> return today.plusDays(1) to today.plusDays(1)
            "days_ago", "weeks_ago", "months_ago", "past_days", "past_weeks" -> {
                val n = number(g.group("n")) ?: return null
                return when (name) {
                    "days_ago" -> today.minusDays(n.toLong()) to today.minusDays(n.toLong())
                    "weeks_ago" -> {
                        val d = today.minusDays(7L * n)
                        val monday = d.minusDays((d.dayOfWeek.value - 1).toLong())
                        monday to monday.plusDays(6)
                    }
                    "months_ago" -> {
                        val (y, m) = shiftMonth(today.year, today.monthValue, n)
                        LocalDate.of(y, m, 1) to monthEnd(y, m)
                    }
                    "past_days" -> today.minusDays(n - 1L) to today
                    else -> today.minusDays(7L * n - 1) to today
                }
            }
            "this_week" -> return today.minusDays((today.dayOfWeek.value - 1).toLong()) to today
            "last_week" -> {
                val monday = today.minusDays((today.dayOfWeek.value - 1 + 7).toLong())
                return monday to monday.plusDays(6)
            }
            "this_month" -> return today.withDayOfMonth(1) to today
            "last_month" -> {
                val (y, m) = shiftMonth(today.year, today.monthValue, 1)
                return LocalDate.of(y, m, 1) to monthEnd(y, m)
            }
            "this_year" -> return LocalDate.of(today.year, 1, 1) to today
            "last_year" -> return LocalDate.of(today.year - 1, 1, 1) to LocalDate.of(today.year - 1, 12, 31)
            "year" -> {
                val y = year!!
                val end = LocalDate.of(y, 12, 31)
                return LocalDate.of(y, 1, 1) to (if (end.isAfter(today)) today else end)
            }
            "weekday" -> {
                val wd = WEEKDAYS.getValue(g.opt("w") ?: g.group("w2"))
                val back = Math.floorMod(today.dayOfWeek.value - 1 - wd, 7).let { if (it == 0) 7 else it }
                val d = today.minusDays(back.toLong())
                return d to d
            }
        }
        error(name)
    }
}
