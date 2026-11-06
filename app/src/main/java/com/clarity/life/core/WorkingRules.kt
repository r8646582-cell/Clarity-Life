package com.clarity.life.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The "what you are working on" rules (docs/CLARITY-V2-BUILD-SPEC.md sections 6 and 7). A 1:1 port of
 * tools/working.py; WorkingRulesTest runs every case in contracts/working_vectors.json.
 *
 * The AI never supplies a date: the keeper returns the owner's own words and [explicitDate] reads a date out of
 * them, or says it is unclear.
 */
object WorkingRules {
    const val MAX_ACTIVE = 3
    const val STALE_DAYS = 21
    const val ROLL_OVER_DAYS = 60
    const val MIN_EVIDENCE_DAYS = 2

    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4, "apr" to 4,
        "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8, "september" to 9,
        "sep" to 9, "sept" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )
    private val MONTH_RE = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private const val DAY = """(\d{1,2})(?:st|nd|rd|th)?"""
    private val DAY_MONTH = Regex("""\b$DAY(?:\s+of)?\s+($MONTH_RE)\b(?:\s*,?\s*(20\d\d))?""")
    private val MONTH_DAY = Regex("""\b($MONTH_RE)\s+$DAY\b(?:\s*,?\s*(20\d\d))?""")
    private val ISO = Regex("""\b(20\d\d)-(\d{2})-(\d{2})\b""")

    private fun valid(y: Int, m: Int, d: Int): LocalDate? = try {
        LocalDate.of(y, m, d)
    } catch (e: java.time.DateTimeException) {
        null
    }

    /** The one calendar date named in [quote], or null (unclear). See tools/working.py for the rules. */
    fun explicitDate(quote: String?, today: String): String? {
        val text = (quote ?: "").lowercase()
        val t = LocalDate.parse(today)
        data class Raw(val day: Int, val month: Int, val year: String?)
        val found = mutableSetOf<Raw>()
        for (m in DAY_MONTH.findAll(text)) {
            found.add(Raw(m.groupValues[1].toInt(), MONTHS.getValue(m.groupValues[2]), m.groupValues[3].ifEmpty { null }))
        }
        for (m in MONTH_DAY.findAll(text)) {
            found.add(Raw(m.groupValues[2].toInt(), MONTHS.getValue(m.groupValues[1]), m.groupValues[3].ifEmpty { null }))
        }
        val dates = mutableSetOf<LocalDate>()
        for (r in found) {
            var dt: LocalDate?
            if (r.year != null) {
                dt = valid(r.year.toInt(), r.month, r.day)
            } else {
                dt = valid(t.year, r.month, r.day)
                if (dt != null && dt.isBefore(t)) {
                    dt = if (ChronoUnit.DAYS.between(dt, t) > ROLL_OVER_DAYS) valid(t.year + 1, r.month, r.day) else null
                }
            }
            if (dt == null) return null
            dates.add(dt)
        }
        for (m in ISO.findAll(text)) {
            val dt = valid(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: return null
            dates.add(dt)
        }
        if (dates.size != 1) return null
        val only = dates.first()
        return if (!only.isBefore(t)) only.toString() else null
    }

    data class Proposal(
        val status: String,
        val confirmed: Int,
        val deadlineState: String,
        val deadlineDate: String?,
        val askTradeoff: Boolean,
    )

    /** What a keeper proposal becomes: at most [MAX_ACTIVE] active, a deadline confirmed only by an explicit date. */
    fun propose(activeCount: Int, deadlineQuote: String?, today: String): Proposal {
        val status = if (activeCount < MAX_ACTIVE) "active" else "paused"
        val quote = (deadlineQuote ?: "").trim()
        val date = if (quote.isEmpty()) null else explicitDate(quote, today)
        val state = if (quote.isEmpty()) "none" else if (date != null) "confirmed" else "unclear"
        return Proposal(status, 0, state, date, status == "paused")
    }

    /** May the keeper make this change? A mood is not a decision. */
    fun changeAllowed(change: String, evidenceDays: Int, tapped: Boolean, venting: Boolean, activeCount: Int): Boolean {
        if (venting) return false
        return when (change) {
            "paused", "dropped" -> tapped || evidenceDays >= MIN_EVIDENCE_DAYS
            "active" -> activeCount < MAX_ACTIVE
            "done", "deadline", "progress" -> true
            else -> false
        }
    }

    fun isStale(lastMentionedDay: String, today: String, askedStale: Boolean): Boolean {
        val gap = ChronoUnit.DAYS.between(LocalDate.parse(lastMentionedDay), LocalDate.parse(today))
        return gap >= STALE_DAYS && !askedStale
    }

    data class Item(
        val name: String,
        val status: String,
        val confirmed: Boolean,
        val deadlineState: String,
        val deadlineDate: String?,
        val sizeText: String?,
        val originQuote: String,
        val originDay: String,
        val lastMentionedDay: String,
    )

    private val SHORT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private fun short(day: String) = LocalDate.parse(day).format(SHORT)

    /** One WORKING_ON line. The day count appears only for a confirmed deadline. */
    fun contextLine(item: Item, today: String): String {
        val t = LocalDate.parse(today)
        val deadline = if (item.deadlineState == "confirmed" && item.deadlineDate != null) {
            val n = ChronoUnit.DAYS.between(t, LocalDate.parse(item.deadlineDate))
            val left = when {
                n > 1 -> "$n days left"
                n == 1L -> "1 day left"
                n == 0L -> "today"
                n == -1L -> "1 day ago"
                else -> "${-n} days ago"
            }
            "deadline ${short(item.deadlineDate)} ($left)"
        } else {
            "deadline: unknown"
        }
        val size = item.sizeText?.takeIf { it.isNotEmpty() }?.let { ", size: $it" }.orEmpty()
        return "${item.name}: ${item.status}, ${if (item.confirmed) "confirmed" else "unconfirmed"}, $deadline$size, " +
            "said on ${short(item.originDay)}: \"${item.originQuote}\", last mentioned ${short(item.lastMentionedDay)}."
    }

    private val TOP = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)

    fun topLine(day: String, activeItems: Int): String {
        val date = LocalDate.parse(day).format(TOP)
        return when {
            activeItems <= 0 -> date
            else -> "$date · Working on $activeItems thing${if (activeItems == 1) "" else "s"}"
        }
    }
}
