package com.clarity.life.core

import com.clarity.life.core.JobFields.CONF
import com.clarity.life.core.JobFields.NOTE_KINDS
import com.clarity.life.core.JobFields.isStr
import com.clarity.life.core.JobFields.optText
import com.clarity.life.core.JobFields.pyInt
import com.clarity.life.core.JobFields.strip
import com.clarity.life.core.JobFields.text
import com.clarity.life.core.JobFields.words

/** Facts from the database the reflection result is checked against. */
data class ReflectionContext(
    /** Only the part of the conversation not reflected before. */
    val hisMessages: List<String>,
    /** Verified quotes already stored from earlier conversations (contradictions may cite them). */
    val hisEarlierQuotes: List<String> = emptyList(),
    val checkinFacts: String = "",
    val notes: Map<Long, NoteFacts> = emptyMap(),
    val deletedNotes: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    /** key -> confirmed by him. */
    val profile: Map<String, Boolean> = emptyMap(),
    val inFocus: Boolean = false,
    /** Unit vectors of the texts compared, from the on-phone model; null for a text without one. */
    val vectors: (String) -> FloatArray? = JobFields.NO_VECTORS,
)

/**
 * Validates the reflection job's output. A 1:1 port of `apply_reflection` in tools/appliers.py.
 *
 * Fail closed, item by item: a broken top level rejects the whole result; a single bad item is dropped and the
 * rest is kept. A new note always starts as a guess; an update may raise a guess to likely only when it was
 * seen again with new verified evidence; the model can never set confirmed; a note he edited or confirmed
 * keeps its text and cannot be retired; a note close to one he deleted is never re-created.
 */
object ReflectionApplier {

    private const val NOTES_ADD = 3
    private const val NOTES_UPDATE = 6
    private const val NOTES_RESOLVE = 5
    private const val PROFILE = 3
    private const val PEOPLE = 3
    private const val EVENTS = 5
    private const val HIS_WORDS = 2
    private const val OBSERVATIONS = 8
    private const val IDEAS = 5
    private const val DISAGREEMENTS = 2
    private const val CONTRADICTIONS = 2

    fun apply(raw: String, ctx: ReflectionContext): ApplyResult {
        val (parsed, error) = JobFields.parse(raw)
        if (parsed == null) return ApplyResult(false, emptyList(), listOf(error ?: "not strict JSON"))
        val dropped = mutableListOf<String>()
        val ops = mutableListOf<JobOp>()
        try {
            run(parsed, ctx, ops, dropped)
        } catch (e: ResultReject) {
            return ApplyResult(false, emptyList(), listOf(e.message ?: "rejected"))
        }
        return ApplyResult(true, ops, dropped)
    }

    private fun run(parsed: Jv, ctx: ReflectionContext, ops: MutableList<JobOp>, dropped: MutableList<String>) {
        val obj = parsed as? Jv.Obj ?: throw ResultReject("not an object")
        val title: String
        val summary: String
        val tone: String
        try {
            title = text(obj.map["title"], 80)
            summary = text(obj.map["summary"], 1500, multiline = true)
            tone = text(obj.map["tone"], 40)
        } catch (e: FieldBad) {
            throw ResultReject("title, summary or tone: ${e.message}")
        }
        val sig = pyInt(obj.map["significance"])
        if (sig == null || sig !in 1..5) throw ResultReject("significance must be 1 to 5")
        val notesObj: Jv.Obj = obj.map["notes"].let {
            if (JobFields.falsy(it)) Jv.Obj(emptyMap()) else it as? Jv.Obj ?: throw ResultReject("notes is not an object")
        }
        val his = ctx.hisMessages
        val facts = ctx.checkinFacts
        ops.add(JobOp.ConversationSet(title, sig.toInt(), tone))

        // Observations are grounding only; nothing is stored from them.
        for (o in JobFields.list(obj, "observations", OBSERVATIONS, dropped)) {
            val q = (o as? Jv.Obj)?.map?.get("quote")?.asStr
            if (q == null || !TextCheck.quoteIsHis(q, his, 1)) dropped.add("observation: quote not found in his messages")
        }

        for (p in JobFields.list(obj, "profile_updates", PROFILE, dropped)) {
            val po = p as? Jv.Obj
            if (po == null) {
                dropped.add("profile: not an object")
                continue
            }
            val key: String
            val value: String
            try {
                key = text(po.map["key"], 40)
                value = text(po.map["value"], 300)
            } catch (e: FieldBad) {
                dropped.add("profile: ${e.message}")
                continue
            }
            val quote = po.map["quote"]?.asStr
            val verified = quote != null && TextCheck.quoteIsHis(quote, his)
            if (ctx.profile[key] == true && !verified) {
                dropped.add("profile: a confirmed entry needs his exact words")
                continue
            }
            ops.add(JobOp.ProfileSet(key, value, verified))
        }

        val knownPeople = ctx.people.associateBy { it.lowercase() }
        for (p in JobFields.list(obj, "people_updates", PEOPLE, dropped)) {
            val po = p as? Jv.Obj
            if (po == null) {
                dropped.add("person: not an object")
                continue
            }
            val name: String
            val rel: String
            val pn: String
            try {
                name = text(po.map["name"], 60)
                rel = optText(po.map["relation"], 60)
                pn = optText(po.map["notes"], 200)
            } catch (e: FieldBad) {
                dropped.add("person: ${e.message}")
                continue
            }
            val known = knownPeople[name.lowercase()]
            ops.add(if (known != null) JobOp.PersonUpdate(known, rel, pn) else JobOp.PersonAdd(name, rel, pn))
        }

        for (ev in JobFields.list(obj, "events", EVENTS, dropped)) {
            val eo = ev as? Jv.Obj
            if (eo == null) {
                dropped.add("event: not an object")
                continue
            }
            val f = try {
                listOf("when", "situation", "feeling_before", "action", "payoff", "outcome").map { optText(eo.map[it], 200) }
            } catch (e: FieldBad) {
                dropped.add("event: ${e.message}")
                continue
            }
            if (f[3].isEmpty() && f[1].isEmpty()) {
                dropped.add("event: no situation and no action")
                continue
            }
            ops.add(JobOp.EventAdd(f[0], f[1], f[2], f[3], f[4], f[5]))
        }

        val notes = ctx.notes
        val active = notes.values.filter { it.status == "active" }.map { it.kind to it.text }.toMutableList()
        val touched = HashSet<Long>()

        for (a in JobFields.list(notesObj, "add", NOTES_ADD, dropped)) {
            val ao = a as? Jv.Obj
            if (ao == null) {
                dropped.add("note.add: not an object")
                continue
            }
            val kind: String
            val noteText: String
            try {
                kind = ao.map["kind"]?.asStr?.takeIf { it in NOTE_KINDS } ?: throw FieldBad("bad kind")
                noteText = text(ao.map["text"], 220)
                if (words(noteText).size > 40) throw FieldBad("over 40 words")
            } catch (e: FieldBad) {
                dropped.add("note.add: ${e.message}")
                continue
            }
            if (JobFields.closeToDeleted(noteText, ctx.deletedNotes, ctx.vectors)) {
                dropped.add("note.add: too close to a note he deleted")
                continue
            }
            if (JobFields.duplicateOfActive(noteText, kind, active, ctx.vectors)) {
                dropped.add("note.add: duplicate of an active note")
                continue
            }
            val ev = verifyEvidence(ao.map["evidence"], his, facts, dropped, "note.add")
            if (ev.isEmpty()) {
                dropped.add("note.add: no verified evidence")
                continue
            }
            ops.add(JobOp.NoteAdd(kind, noteText, "guess", ev))
            active.add(kind to noteText)
        }

        for (u in JobFields.list(notesObj, "update", NOTES_UPDATE, dropped)) {
            val uo = u as? Jv.Obj
            val nid = pyInt(uo?.map?.get("id"))
            val n = nid?.let { notes[it] }
            if (uo == null || nid == null || n == null) {
                dropped.add("note.update: unknown id")
                continue
            }
            if (n.status != "active" || nid in touched) {
                dropped.add("note.update $nid: not active or already changed")
                continue
            }
            // A missing evidence key means none; an explicit null is a bad evidence list.
            val evRaw = if (uo.map.containsKey("evidence")) uo.map["evidence"] else Jv.Arr(emptyList())
            val ev = verifyEvidence(evRaw, his, facts, dropped, "note.update $nid")
            val rawText = uo.map["text"]?.takeIf { it != Jv.Null }
            var newText: String? = null
            if (rawText != null && !n.edited && n.confidence != "confirmed") {
                try {
                    newText = text(rawText, 220)
                } catch (e: FieldBad) {
                    dropped.add("note.update $nid: ${e.message}")
                    continue
                }
                if (JobFields.closeToDeleted(newText, ctx.deletedNotes, ctx.vectors)) {
                    dropped.add("note.update $nid: new text too close to a note he deleted")
                    continue
                }
            } else if (rawText != null && !isStr(rawText, n.text)) {
                dropped.add("note.update $nid: his own note, text kept")
            }
            var conf = n.confidence
            val want: Jv = if (uo.map.containsKey("confidence")) uo.map.getValue("confidence") else Jv.Str(conf)
            val wantStr = want.asStr
            val seenAgainFlag = uo.map["seen_again"] == Jv.Bool(true)
            if (wantStr != null && wantStr in CONF && CONF.indexOf(wantStr) < CONF.indexOf(conf) &&
                !n.edited && conf != "confirmed"
            ) {
                conf = wantStr // lowering is always safe
            } else if (wantStr == "likely" && conf == "guess" && seenAgainFlag && ev.isNotEmpty()) {
                conf = "likely" // one step, with new evidence
            } else if (wantStr != conf) {
                dropped.add("note.update $nid: confidence change not allowed")
            }
            val seen = seenAgainFlag && ev.isNotEmpty()
            if (newText == null && conf == n.confidence && ev.isEmpty()) {
                dropped.add("note.update $nid: changes nothing")
                continue
            }
            touched.add(nid)
            ops.add(JobOp.NoteUpdate(nid, newText, conf, seen, ev))
        }

        for (r in JobFields.list(notesObj, "resolve", NOTES_RESOLVE, dropped)) {
            val rid = pyInt(r)
            val n = rid?.let { notes[it] }
            if (rid == null || n == null) {
                dropped.add("note.resolve: unknown id")
            } else if (n.status != "active" || n.edited || n.confidence == "confirmed" || rid in touched) {
                dropped.add("note.resolve $rid: protected or already changed")
            } else {
                touched.add(rid)
                ops.add(JobOp.NoteRetire(rid))
            }
        }

        for (q in JobFields.list(obj, "his_words", HIS_WORDS, dropped)) {
            val s = q.asStr
            if (s != null && TextCheck.quoteIsHis(s, his)) ops.add(JobOp.QuoteAdd(strip(s)))
            else dropped.add("his_words: not found word for word")
        }

        val everythingHis = his + ctx.hisEarlierQuotes
        for (d in JobFields.list(obj, "disagreements", DISAGREEMENTS, dropped)) {
            val dob = d as? Jv.Obj
            if (dob == null) {
                dropped.add("disagreement: not an object")
                continue
            }
            val claim = try {
                text(dob.map["claim"], 200)
            } catch (e: FieldBad) {
                dropped.add("disagreement: ${e.message}")
                continue
            }
            val pos = dob.map["his_position"]?.asStr
            if (pos != null && TextCheck.quoteIsHis(pos, his)) ops.add(JobOp.DisagreementAdd(claim, strip(pos)))
            else dropped.add("disagreement: his position not found word for word")
        }

        for (c in JobFields.list(obj, "contradictions", CONTRADICTIONS, dropped)) {
            val co = c as? Jv.Obj
            val qa = co?.map?.get("quote_a")?.asStr
            val qb = co?.map?.get("quote_b")?.asStr
            if (qa != null && qb != null && TextCheck.quoteIsHis(qa, everythingHis) &&
                TextCheck.quoteIsHis(qb, everythingHis) && TextCheck.norm(qa) != TextCheck.norm(qb)
            ) {
                ops.add(JobOp.ContradictionAdd(strip(qa), strip(qb)))
            } else {
                dropped.add("contradiction: quotes not found word for word")
            }
        }

        for (tag in JobFields.list(obj, "ideas_used", IDEAS, dropped)) {
            try {
                ops.add(JobOp.IdeaAdd(text(tag, 40)))
            } catch (e: FieldBad) {
                dropped.add("idea: bad tag")
            }
        }

        val fs = obj.map["focus_summary"]
        if (fs != null && fs != Jv.Null) {
            if (!ctx.inFocus) {
                dropped.add("focus_summary: this chat is not in a focus")
            } else {
                try {
                    val t = text(fs, 2000, multiline = true)
                    if (words(t).size > 260) throw FieldBad("over 260 words")
                    ops.add(JobOp.FocusSummary(t))
                } catch (e: FieldBad) {
                    dropped.add("focus_summary: ${e.message}")
                }
            }
        }

        ops.add(JobOp.ConversationSummary(summary))
    }

    private fun verifyEvidence(
        items: Jv?,
        his: List<String>,
        checkinFacts: String,
        dropped: MutableList<String>,
        where: String,
    ): List<Evidence> {
        val ok = mutableListOf<Evidence>()
        val list = items?.asList
        if (list == null) {
            dropped.add("$where: evidence is not a list")
            return ok
        }
        for (e in list) {
            val eo = e as? Jv.Obj
            val kind = eo?.map?.get("kind")?.asStr
            val t = eo?.map?.get("text")?.asStr
            if (kind == null || t == null || (kind != "quote" && kind != "count")) {
                dropped.add("$where: bad evidence item")
                continue
            }
            val flat = words(t).joinToString(" ")
            if (kind == "quote" && TextCheck.quoteIsHis(t, his)) {
                ok.add(Evidence("quote", strip(t)))
            } else if (kind == "count" && JobFields.pyLen(strip(t)) >= 6 && countInFacts(flat, checkinFacts)) {
                ok.add(Evidence("count", flat))
            } else {
                dropped.add("$where: unverified $kind evidence")
            }
        }
        return ok
    }

    /** A count is copied from one CHECKIN_FACTS line, whole: "2 answered of 2" is not in "12 answered of 20". */
    private fun countInFacts(flat: String, checkinFacts: String): Boolean {
        if (flat.isEmpty()) return false
        for (line in checkinFacts.split("\n")) {
            val fl = words(line).joinToString(" ")
            var i = fl.indexOf(flat)
            while (i >= 0) {
                val j = i + flat.length
                val leftOk = i == 0 || !flat.first().isLetterOrDigit() || !fl[i - 1].isLetterOrDigit()
                val rightOk = j == fl.length || !flat.last().isLetterOrDigit() || !fl[j].isLetterOrDigit()
                if (leftOk && rightOk) return true
                i = fl.indexOf(flat, i + 1)
            }
        }
        return false
    }
}
