package com.clarity.life.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What the monthly gardening job (prompts/gardening.md) is given, written by code. Dates and spans are
 * worked out here, never by the model: a note gets the mark "quiet 60+ days" when its last sighting is 60
 * days or more before today.
 */
object GardeningInput {

    /** Gardening reads the session summaries of about the last three months. */
    const val SUMMARY_MONTHS = 3L

    /** Spec section 7 and gardening.md: patterns and threads not seen for 60 days may be retired. */
    const val QUIET_DAYS = 60L

    data class Note(
        val id: Long,
        val kind: String,
        val confidence: String,
        val timesSeen: Int,
        val firstSeen: String,
        val lastSeen: String,
        val evidence: Int,
        val edited: Boolean,
        val text: String,
    )

    data class Person(val id: Long, val name: String, val relation: String, val notes: String)

    data class Profile(val key: String, val value: String, val confirmed: Boolean)

    data class Summary(val day: String, val title: String, val text: String)

    /** The job key: one gardening per calendar month (by his day key), and one extra when a kind is full. */
    fun monthKey(today: LocalDate): String = "gardening:" + today.toString().take(7)

    /** A kind at its cap blocks every new note of that kind, so it is tidied within the week, not next month. */
    fun capKey(today: LocalDate): String =
        "gardening:full:" + today.minusDays((today.dayOfWeek.value - 1).toLong())

    fun quiet(lastSeen: String, today: LocalDate): Boolean {
        val last = runCatching { LocalDate.parse(lastSeen) }.getOrNull() ?: return false
        return ChronoUnit.DAYS.between(last, today) >= QUIET_DAYS
    }

    /**
     * Worth a call only when there is something it could tidy: two notes it may touch, two people it could
     * merge, or a profile entry it may rewrite. Otherwise the month is settled with no call.
     */
    fun worthRunning(notes: List<Note>, people: List<Person>, profile: List<Profile>): Boolean =
        notes.count { !it.edited && it.confidence != "confirmed" } >= 2 ||
            people.size >= 2 ||
            profile.any { !it.confirmed }

    fun notes(notes: List<Note>, today: LocalDate): String {
        if (notes.isEmpty()) return "NOTES\n(none)"
        val e = ContextBuilder::escape
        return "NOTES\n" + notes.joinToString("\n") { n ->
            val marks = buildList {
                add("${n.kind}, ${n.confidence}")
                add("seen ${n.timesSeen} ${if (n.timesSeen == 1) "time" else "times"}")
                add("first seen ${KnowText.shortDay(n.firstSeen)}, last seen ${KnowText.shortDay(n.lastSeen)}")
                add("${n.evidence} evidence ${if (n.evidence == 1) "row" else "rows"}")
                if (n.edited) add("edited by them")
                if (n.confidence == "confirmed") add("confirmed by them")
                if (quiet(n.lastSeen, today)) add("quiet 60+ days")
            }
            "- [id ${n.id}] (${marks.joinToString("; ")}) ${e(n.text)}"
        }
    }

    // ---------- suggestions (Max plan Phase 3 step 5), a port of gardening_clusters in tools/appliers.py ----------

    const val CLUSTER_MEANING = 0.85
    const val CLUSTER_WORDS = 0.5
    const val CLUSTER_SIZE = 4
    const val CLUSTERS = 10

    private class Pair3(val score: Double, val a: Long, val b: Long)

    /**
     * Notes the code finds close, for gardening to consider merging. Suggestions only: the model decides, and
     * [GardeningApplier] checks every merge as before. Only notes a job may touch (active, not edited, not
     * confirmed), of the same kind, saying "not" the same way, close in meaning ([CLUSTER_MEANING], from the
     * on-phone model) or in words ([CLUSTER_WORDS]). The closest pairs join first; a group never grows past
     * [CLUSTER_SIZE]. Groups come out by their lowest id, each sorted, at most [CLUSTERS].
     */
    fun clusters(notes: Map<Long, NoteFacts>, vectors: (String) -> FloatArray?): List<List<Long>> {
        val free = notes.filter { (_, n) -> n.status == "active" && !n.edited && n.confidence != "confirmed" }.keys.sorted()
        val pairs = ArrayList<Pair3>()
        for (x in free.indices) {
            for (y in x + 1 until free.size) {
                val a = notes.getValue(free[x])
                val b = notes.getValue(free[y])
                if (a.kind != b.kind || JobFields.negated(a.text) != JobFields.negated(b.text)) continue
                val m = JobFields.meaning(vectors, a.text, b.text)
                val w = JobFields.similarity(a.text, b.text)
                if ((m != null && m >= CLUSTER_MEANING) || w >= CLUSTER_WORDS) pairs += Pair3(m ?: w, free[x], free[y])
            }
        }
        pairs.sortWith(compareByDescending<Pair3> { it.score }.thenBy { it.a }.thenBy { it.b })
        val group = HashMap<Long, MutableList<Long>>()
        for (i in free) group[i] = mutableListOf(i)
        for (p in pairs) {
            val ga = group.getValue(p.a)
            val gb = group.getValue(p.b)
            if (ga === gb || ga.size + gb.size > CLUSTER_SIZE) continue
            ga.addAll(gb)
            for (i in gb) group[i] = ga
        }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MutableList<Long>, Boolean>())
        val out = ArrayList<List<Long>>()
        for (i in free) {
            val g = group.getValue(i)
            if (g.size >= 2 && seen.add(g)) out += g.sorted()
        }
        return out.take(CLUSTERS)
    }

    /** The suggestions under NOTES, or nothing when there are none. */
    fun closeGroups(groups: List<List<Long>>): String =
        if (groups.isEmpty()) {
            ""
        } else {
            "\n\nCLOSE IN MEANING OR WORDING (found by the app: notes to consider merging, not a fact; merge only " +
                "those that truly say the same thing)\n" + groups.joinToString("\n") { g -> "- " + g.joinToString(", ") { "[id $it]" } }
        }

    fun profile(entries: List<Profile>): String {
        if (entries.isEmpty()) return "PROFILE\n(none)"
        val e = ContextBuilder::escape
        return "PROFILE\n" + entries.joinToString("\n") {
            "- ${e(it.key)}: ${e(it.value)}" + if (it.confirmed) " (confirmed)" else ""
        }
    }

    fun people(people: List<Person>): String {
        if (people.isEmpty()) return "PEOPLE\n(none)"
        val e = ContextBuilder::escape
        return "PEOPLE\n" + people.joinToString("\n") { p ->
            "- [id ${p.id}] ${e(p.name)}" + (if (p.relation.isNotBlank()) " (${e(p.relation)})" else "") +
                (if (p.notes.isNotBlank()) ": ${e(p.notes)}" else "")
        }
    }

    fun summaries(sessions: List<Summary>): String {
        if (sessions.isEmpty()) return "SUMMARIES\n(none)"
        val e = ContextBuilder::escape
        return "SUMMARIES\n" + sessions.joinToString("\n") { s ->
            val title = if (s.title.isNotBlank()) " \"${e(s.title)}\"" else ""
            "- ${KnowText.shortDay(s.day)}$title: ${e(s.text)}"
        }
    }
}
