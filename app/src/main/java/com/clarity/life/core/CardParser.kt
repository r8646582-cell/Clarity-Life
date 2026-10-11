package com.clarity.life.core

import kotlin.math.abs

/**
 * The card parser: the one gate between what the model writes and anything the app may offer to save.
 *
 * A 1:1 port of tools/cards.py; CardParserTest runs every case in contracts/card_vectors.json.
 *
 * Design: FAIL CLOSED. A card that is not exactly right is dropped and logged without content, and a
 * dropped card never changes state. The prose is never lost because of a bad card, and a stray "[[" in
 * the prose is removed so a raw marker is never shown.
 *
 * Nothing here saves anything: a card is only ever an offer until the owner taps its button.
 */
class CardBad(message: String) : Exception(message)

/** Ids and review options the app put into the context, so the parser can reject anything invented. */
data class KnownIds(
    val goals: Set<String> = emptySet(),
    val targets: Set<String> = emptySet(),
    val backlogs: Set<String> = emptySet(),
    val focuses: Set<String> = emptySet(),
    val reviewOptions: Set<String> = emptySet(),
) {
    fun forKind(kind: String): Set<String> = when (kind) {
        "goal" -> goals
        "target" -> targets
        "backlog" -> backlogs
        "focus" -> focuses
        else -> emptySet()
    }
}

/** A validated, normalised card. [fields] holds exactly the fields the reference parser keeps. */
data class Card(val type: String, val fields: Map<String, Jv>) {
    fun str(key: String): String? = fields[key]?.asStr
    fun num(key: String): Double? = fields[key]?.asNum
    fun bool(key: String): Boolean? = fields[key]?.asBool
    fun list(key: String): List<Jv>? = fields[key]?.asList

    /** The value as the test bench writes it in a card_field check (card_field=promise.remind=false). */
    fun fieldAsText(key: String): String? = when (val v = fields[key]) {
        null -> null
        is Jv.Str -> v.value
        is Jv.Bool -> v.value.toString()
        is Jv.Num -> if (v.isInteger || abs(v.value - v.value.toLong()) < Planner.EPS) {
            v.value.toLong().toString()
        } else {
            v.value.toString()
        }
        else -> null
    }
}

data class ParsedReply(val prose: String, val cards: List<Card>, val dropped: List<String>)

object CardParser {

    /** What the app records after one reply: the prose it shows, and counts with no content in them. */
    data class ParseOutcome(val prose: String, val cardsOffered: Int, val cardsDropped: Int)

    /** A validated card as JSON, for card.payload_json. */
    fun toJson(card: Card): String = StrictJson.write(
        Jv.Obj(linkedMapOf<String, Jv>("type" to Jv.Str(card.type)).also { it.putAll(card.fields) }),
    )

    val CARD_TYPES = listOf("promise", "plan", "target_change", "backlog", "focus", "note", "mode")

    /** Which cards each chat type may show (spec section 5). The app drops any card a type does not allow. */
    val CHAT_TYPES = mapOf(
        "free" to listOf("promise", "focus", "mode", "note"),
        "untangle" to listOf("mode", "note"),
        "decide" to listOf("promise", "mode", "note"),
        "plan" to listOf("plan", "target_change", "backlog", "promise", "mode", "note"),
    )

    const val MAX_CARDS = 3

    /** No target, minimum or total is ever this large; bigger means a broken card. */
    const val MAX_NUMBER = 100000.0

    val NOTE_KINDS = listOf("pattern", "what_helps", "what_doesnt", "value", "thread")
    val MODE_TARGETS = listOf("free", "untangle", "decide", "plan", "focus")

    private val LINE_RE = Regex("""^\[\[suggest:\s*(\{.*\})\s*\]\]$""") // braces escaped: Android's regex engine rejects a bare }
    private val ID_RE = mapOf(
        "goal" to Regex("""^G\d+$"""),
        "target" to Regex("""^T\d+$"""),
        "backlog" to Regex("""^B\d+$"""),
        "focus" to Regex("""^F\d+$"""),
    )

    private fun hasControlChar(s: String) = s.any { it.code < 0x20 || it.code == 0x7f }

    private fun str(d: Map<String, Jv>, key: String, maxLen: Int, required: Boolean = true): String? {
        val v = d[key] ?: if (required) throw CardBad("missing $key") else return null
        if (v is Jv.Null) { if (required) throw CardBad("missing $key") else return null }
        val s = v.asStr ?: throw CardBad("bad $key")
        if (s.isBlank()) throw CardBad("bad $key")
        if (s.length > maxLen) throw CardBad("too long $key")
        if (hasControlChar(s)) throw CardBad("control character in $key")
        if (s.contains("[[") || s.contains("]]")) throw CardBad("brackets in $key")
        return s.trim()
    }

    private fun num(
        d: Map<String, Jv>,
        key: String,
        required: Boolean = true,
        minimum: Double = 0.0,
        strictPositive: Boolean = false,
    ): Jv.Num? {
        val v = d[key] ?: if (required) throw CardBad("missing $key") else return null
        if (v is Jv.Null) { if (required) throw CardBad("missing $key") else return null }
        if (v is Jv.Bool || v !is Jv.Num) throw CardBad("not a number $key")
        if (!v.value.isFinite() || abs(v.value) > MAX_NUMBER) throw CardBad("out of range $key")
        if (strictPositive && v.value <= 0) throw CardBad("must be above zero $key")
        if (v.value < minimum) throw CardBad("too small $key")
        return v
    }

    private fun id(
        d: Map<String, Jv>,
        key: String,
        kind: String,
        known: KnownIds,
        required: Boolean = true,
    ): String? {
        val v = d[key] ?: if (required) throw CardBad("missing $key") else return null
        if (v is Jv.Null) { if (required) throw CardBad("missing $key") else return null }
        val s = v.asStr
        if (s == null || !ID_RE.getValue(kind).matches(s)) throw CardBad("bad id $key")
        if (s !in known.forKind(kind)) throw CardBad("unknown id $s")
        return s
    }

    private fun schedule(v: Jv?): String {
        val s = v?.asStr ?: throw CardBad("bad schedule")
        try {
            Planner.parseSchedule(s)
        } catch (e: PlannerError) {
            throw CardBad("bad schedule")
        }
        return s.trim()
    }

    private fun targetItem(item: Jv): Jv.Obj {
        val t = item.asObj?.map ?: throw CardBad("target item")
        val out = LinkedHashMap<String, Jv>()
        out["name"] = Jv.Str(str(t, "name", 60)!!)
        val target = num(t, "target", strictPositive = true)!!
        out["target"] = target
        val mn = num(t, "min", required = false)
        if (mn != null) {
            if (mn.value > target.value) throw CardBad("min above target")
            out["min"] = mn
        }
        out["schedule"] = Jv.Str(schedule(t["schedule"]))
        for (k in listOf("unit", "start", "end")) {
            val s = str(t, k, 40, required = false)
            if (!s.isNullOrEmpty()) out[k] = Jv.Str(s)
        }
        return Jv.Obj(out)
    }

    private fun backlogItem(item: Jv): Jv.Obj {
        val b = item.asObj?.map ?: throw CardBad("backlog item")
        return Jv.Obj(
            linkedMapOf(
                "name" to Jv.Str(str(b, "name", 60)!!),
                "total" to num(b, "total", strictPositive = true)!!,
                "unit" to Jv.Str(str(b, "unit", 20)!!),
                "deadline" to Jv.Str(str(b, "deadline", 40)!!),
            ),
        )
    }

    /** Returns a normalised card or throws [CardBad]. */
    fun validate(card: Jv, known: KnownIds): Card {
        val c = card.asObj?.map ?: throw CardBad("not an object")
        val t = c["type"]?.asStr
        if (t == null || t !in CARD_TYPES) throw CardBad("unknown type")
        val out = LinkedHashMap<String, Jv>()
        when (t) {
            "promise" -> {
                out["text"] = Jv.Str(str(c, "text", 140)!!)
                out["when"] = Jv.Str(str(c, "when", 40)!!)
                val r = c["remind"]
                val remind = when {
                    r == null -> false
                    r is Jv.Bool -> r.value
                    else -> throw CardBad("remind must be true or false")
                }
                out["remind"] = Jv.Bool(remind)
                val why = str(c, "why", 140, required = false)
                if (!why.isNullOrEmpty()) out["why"] = Jv.Str(why)
            }

            "plan" -> {
                out["title"] = Jv.Str(str(c, "title", 80)!!)
                out["rationale"] = Jv.Str(str(c, "rationale", 300)!!)
                val g = id(c, "goal", "goal", known, required = false)
                if (g != null) out["goal"] = Jv.Str(g)
                val targets = c["targets"] ?: Jv.Arr(emptyList())
                val backlog = c["backlog"] ?: Jv.Arr(emptyList())
                val targetList = targets.asList ?: throw CardBad("targets and backlog must be lists")
                val backlogList = backlog.asList ?: throw CardBad("targets and backlog must be lists")
                if (targetList.size > 4 || backlogList.size > 2) throw CardBad("too many items")
                if (targetList.isEmpty() && backlogList.isEmpty()) {
                    throw CardBad("plan needs a target or a backlog item")
                }
                out["targets"] = Jv.Arr(targetList.map { targetItem(it) })
                out["backlog"] = Jv.Arr(backlogList.map { backlogItem(it) })
                val rd = str(c, "review_date", 40)!!
                // Fail closed: never accept a review date the app did not offer in PLAN_STATE.
                if (known.reviewOptions.isEmpty()) throw CardBad("no review options were offered")
                if (rd !in known.reviewOptions) throw CardBad("review_date is not one of the review options")
                out["review_date"] = Jv.Str(rd)
            }

            "target_change" -> {
                out["target"] = Jv.Str(id(c, "target", "target", known)!!)
                var changed = false
                for (k in listOf("target_value", "min")) {
                    val v = num(c, k, required = false, strictPositive = k == "target_value")
                    if (v != null) {
                        out[k] = v
                        changed = true
                    }
                }
                val tv = out["target_value"]?.asNum
                val mn = out["min"]?.asNum
                if (tv != null && mn != null && mn > tv) throw CardBad("min above target")
                // when only one of the two is given, the app checks it against the stored value at save time
                if (c.containsKey("schedule")) {
                    out["schedule"] = Jv.Str(schedule(c["schedule"]))
                    changed = true
                }
                val e = str(c, "end", 40, required = false)
                if (!e.isNullOrEmpty()) {
                    out["end"] = Jv.Str(e)
                    changed = true
                }
                if (!changed) throw CardBad("target_change changes nothing")
                out["reason"] = Jv.Str(str(c, "reason", 200)!!)
            }

            "backlog" -> {
                val b = id(c, "backlog", "backlog", known, required = false)
                if (b != null) {
                    out["backlog"] = Jv.Str(b)
                } else {
                    out["name"] = Jv.Str(str(c, "name", 60)!!)
                    out["unit"] = Jv.Str(str(c, "unit", 20)!!)
                }
                val total = num(c, "total", required = b == null, strictPositive = true)
                if (total != null) out["total"] = total
                val deadline = str(c, "deadline", 40, required = b == null)
                if (deadline != null) out["deadline"] = Jv.Str(deadline)
                if (b != null && total == null && deadline == null) {
                    throw CardBad("backlog change changes nothing")
                }
                out["reason"] = Jv.Str(str(c, "reason", 200)!!)
            }

            "focus" -> {
                out["name"] = Jv.Str(str(c, "name", 60)!!)
                out["why"] = Jv.Str(str(c, "why", 200)!!)
            }

            "note" -> {
                val k = c["kind"]?.asStr
                if (k == null || k !in NOTE_KINDS) throw CardBad("bad note kind")
                out["kind"] = Jv.Str(k)
                out["text"] = Jv.Str(str(c, "text", 200)!!)
            }

            "mode" -> {
                val to = c["to"]?.asStr
                if (to == null || to !in MODE_TARGETS) throw CardBad("bad mode")
                out["to"] = Jv.Str(to)
                if (to == "focus") out["focus"] = Jv.Str(id(c, "focus", "focus", known)!!)
            }
        }
        return Card(t, out)
    }

    /**
     * Split a model reply into prose, cards and the reasons anything was dropped.
     *
     * Only trailing lines of the exact form `[[suggest: {...}]]` can become cards. Any other line
     * containing "[[" is removed from the prose (never shown raw) and counted as dropped.
     */
    fun parseReply(text: String, chatType: String, known: KnownIds = KnownIds()): ParsedReply {
        val allowed = CHAT_TYPES[chatType] ?: throw CardBad("unknown chat type $chatType")
        val lines = text.replace("\r\n", "\n").split("\n")
        var end = lines.size
        while (end > 0 && lines[end - 1].isBlank()) end--
        var start = end
        while (start > 0 && (lines[start - 1].trim().startsWith("[[suggest:") || lines[start - 1].isBlank())) start--
        val tail = lines.subList(start, end).map { it.trim() }.filter { it.isNotEmpty() }
        val head = lines.subList(0, start)

        val cards = mutableListOf<Card>()
        val dropped = mutableListOf<String>()
        val seen = HashMap<String, Int>()
        for (raw in tail) {
            val m = LINE_RE.find(raw)
            if (m == null) {
                dropped.add("shape")
                continue
            }
            val card = try {
                validate(StrictJson.parse(m.groupValues[1]), known)
            } catch (e: JsonError) {
                dropped.add(e.message ?: "bad json")
                continue
            } catch (e: CardBad) {
                dropped.add(e.message ?: "bad card")
                continue
            }
            if (card.type !in allowed) {
                dropped.add("not allowed in $chatType")
                continue
            }
            val limit = if (card.type == "promise") 2 else 1
            if ((seen[card.type] ?: 0) >= limit) {
                dropped.add("duplicate ${card.type}")
                continue
            }
            if (cards.size >= MAX_CARDS) {
                dropped.add("too many cards")
                continue
            }
            seen[card.type] = (seen[card.type] ?: 0) + 1
            cards.add(card)
        }

        val clean = mutableListOf<String>()
        for (l in head) {
            if (l.contains("[[") || l.contains("]]")) {
                dropped.add("stray brackets in prose")
                continue
            }
            clean.add(l)
        }
        // a code fence left empty by removing card lines is noise: drop the empty pair
        val out = mutableListOf<String>()
        for (l in clean) {
            if (l.trim().startsWith("```") && out.isNotEmpty() && out.last().trim().startsWith("```")) {
                out.removeAt(out.size - 1)
                continue
            }
            out.add(l)
        }
        return ParsedReply(out.joinToString("\n").trimEnd(), cards, dropped)
    }
}
