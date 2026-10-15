package com.clarity.life.core

import com.clarity.life.core.JobFields.CONF
import com.clarity.life.core.JobFields.optText
import com.clarity.life.core.JobFields.pyInt
import com.clarity.life.core.JobFields.text

/** Facts from the database the gardening result is checked against. */
data class GardeningContext(
    val notes: Map<Long, NoteFacts>,
    val people: Set<Long> = emptySet(),
    /** key -> confirmed by him. */
    val profile: Map<String, Boolean> = emptyMap(),
    /** The notes he deleted: no merged or rewritten text may come close to one (rule 5). */
    val deletedNotes: List<String> = emptyList(),
    /** Unit vectors of the texts compared, from the on-phone model; null for a text without one. */
    val vectors: (String) -> FloatArray? = JobFields.NO_VECTORS,
)

/**
 * Validates the monthly gardening job's output. A 1:1 port of `apply_gardening` in tools/appliers.py.
 *
 * Gardening only tidies: it merges, rewrites or retires notes and merges people. A note he edited or
 * confirmed is never touched, each note takes at most one operation, a merge keeps the highest existing
 * confidence (never raises it), a confirmed profile entry is never rewritten, and no merged or rewritten text
 * comes close to a note he deleted, in words or in meaning.
 */
object GardeningApplier {

    fun apply(raw: String, ctx: GardeningContext): ApplyResult {
        val (parsed, error) = JobFields.parse(raw)
        if (parsed == null) return ApplyResult(false, emptyList(), listOf(error ?: "not strict JSON"))
        val obj = parsed as? Jv.Obj ?: return ApplyResult(false, emptyList(), listOf("not an object"))
        val dropped = mutableListOf<String>()
        val ops = mutableListOf<JobOp>()
        val notes = ctx.notes
        val used = HashSet<Long>()

        fun free(i: Long): Boolean {
            val n = notes[i] ?: return false
            return n.status == "active" && !n.edited && n.confidence != "confirmed" && i !in used
        }

        // Unlike reflection, a section that is not a list is simply ignored here.
        fun lst(key: String): List<Jv> = obj.map[key]?.asList ?: emptyList()

        /** Whole-number ids, at least two, no repeats; null when any of that fails. */
        fun idList(m: Jv.Obj?): List<Long>? {
            val raw = m?.map?.get("ids")?.asList ?: return null
            if (raw.size < 2) return null
            val ids = raw.map { pyInt(it) ?: return null }
            return ids.takeIf { it.toSet().size == it.size }
        }

        for (m in lst("merge")) {
            val mo = m as? Jv.Obj
            val ids = idList(mo)
            if (mo == null || ids == null || !ids.all { free(it) }) {
                dropped.add("merge: ids missing, protected or already used")
                continue
            }
            val kinds = ids.map { notes.getValue(it).kind }.toSet()
            val kind = mo.map["kind"]?.asStr
            if (kinds.size != 1 || kind == null || kind !in kinds) {
                dropped.add("merge: notes of different kinds")
                continue
            }
            val top = ids.map { notes.getValue(it).confidence }.maxBy { CONF.indexOf(it) }
            if (mo.map["confidence"]?.asStr != top) {
                dropped.add("merge: confidence must be the highest existing ($top)")
                continue
            }
            val t = try {
                text(mo.map["text"], 220)
            } catch (e: FieldBad) {
                dropped.add("merge: ${e.message}")
                continue
            }
            if (JobFields.closeToDeleted(t, ctx.deletedNotes, ctx.vectors)) {
                dropped.add("merge: too close to a note he deleted")
                continue
            }
            used.addAll(ids)
            ops.add(JobOp.NoteMerge(ids.sorted(), t, top, ids.sumOf { notes.getValue(it).timesSeen }))
        }

        for (r in lst("rewrite")) {
            val ro = r as? Jv.Obj
            val i = pyInt(ro?.map?.get("id"))
            if (ro == null || i == null || !free(i)) {
                dropped.add("rewrite: id missing, protected or already used")
                continue
            }
            val t = try {
                text(ro.map["text"], 220)
            } catch (e: FieldBad) {
                dropped.add("rewrite: ${e.message}")
                continue
            }
            if (JobFields.closeToDeleted(t, ctx.deletedNotes, ctx.vectors)) {
                dropped.add("rewrite: too close to a note he deleted")
                continue
            }
            used.add(i)
            ops.add(JobOp.NoteRewrite(i, t))
        }

        for (r in lst("retire")) {
            val i = pyInt((r as? Jv.Obj)?.map?.get("id"))
            if (i == null || !free(i)) {
                dropped.add("retire: id missing, protected or already used")
                continue
            }
            used.add(i)
            ops.add(JobOp.NoteRetire(i))
        }

        val usedPeople = HashSet<Long>()
        for (m in lst("merge_people")) {
            val mo = m as? Jv.Obj
            val ids = idList(mo)
            if (mo == null || ids == null || !ids.all { it in ctx.people && it !in usedPeople }) {
                dropped.add("merge_people: unknown or repeated ids")
                continue
            }
            val name: String
            val rel: String
            val pn: String
            try {
                name = text(mo.map["name"], 60)
                rel = optText(mo.map["relation"], 60)
                pn = optText(mo.map["notes"], 200)
            } catch (e: FieldBad) {
                dropped.add("merge_people: ${e.message}")
                continue
            }
            usedPeople.addAll(ids)
            ops.add(JobOp.PersonMerge(ids.sorted(), name, rel, pn))
        }

        for (p in lst("rewrite_profile")) {
            val po = p as? Jv.Obj
            val key = po?.map?.get("key")?.asStr
            val confirmed = key?.let { ctx.profile[it] }
            if (po == null || key == null || confirmed == null || confirmed) {
                dropped.add("rewrite_profile: unknown or confirmed key")
                continue
            }
            try {
                ops.add(JobOp.ProfileSet(key, text(po.map["value"], 300), false))
            } catch (e: FieldBad) {
                dropped.add("rewrite_profile: ${e.message}")
            }
        }
        return ApplyResult(true, ops, dropped)
    }
}
