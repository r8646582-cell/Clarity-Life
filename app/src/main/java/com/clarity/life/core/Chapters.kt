package com.clarity.life.core

import java.time.LocalDate

/**
 * Quarterly chapters (spec section 8, prompts/chapter.md): which quarter is due, its job key, when one is worth
 * writing, and the CHAPTERS block of the context packet. Pure, so the dates are tested on the JVM; the model
 * never works out a period.
 *
 * Quarters are calendar quarters: January to March, April to June, July to September, October to December.
 */
object Chapters {

    data class Quarter(val start: LocalDate, val end: LocalDate) {
        val key: String get() = "chapter:$start"

        /** "Jul to Sep 2026", the words the chapter prompt and What I know use. */
        val label: String get() = "${MONTHS[start.monthValue - 1]} to ${MONTHS[end.monthValue - 1]} ${end.year}"
    }

    data class Chapter(val periodStart: String, val periodEnd: String, val title: String, val body: String)

    /** At most this many chapters go in the context (contracts/context.md). */
    const val IN_CONTEXT = 2

    /** A quarter with fewer reflected conversations than this has nothing to say: no chapter, no call. */
    const val MIN_SESSIONS = 3

    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Ranks above every memory block's, so chapters are the last thing trimmed; the older one goes first. */
    private const val CHAPTER_RANK = 1000

    fun quarterOf(day: LocalDate): Quarter {
        val first = LocalDate.of(day.year, (day.monthValue - 1) / 3 * 3 + 1, 1)
        return Quarter(first, first.plusMonths(3).minusDays(1))
    }

    /** The quarter that ended most recently before [today]: the only one a chapter is written for. */
    fun latestFinished(today: LocalDate): Quarter = quarterOf(quarterOf(today).start.minusDays(1))

    /** Worth a model call only when the quarter had enough reflected conversations to tell a story. */
    fun worthWriting(reflectedSessions: Int): Boolean = reflectedSessions >= MIN_SESSIONS

    /** CHAPTERS: the last [IN_CONTEXT] chapters, older first, each with its period. [chapters] newest first. */
    fun block(chapters: List<Chapter>): Block {
        val kept = chapters.take(IN_CONTEXT)
        return Block(
            "CHAPTERS",
            kept.mapIndexed { i, c ->
                val q = Quarter(LocalDate.parse(c.periodStart), LocalDate.parse(c.periodEnd))
                BlockItem("${q.label}: ${c.title}\n${c.body}", CHAPTER_RANK + i + 1)
            }.reversed(),
        )
    }
}
