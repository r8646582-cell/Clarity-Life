package com.clarity.life.memory

import androidx.room.withTransaction
import com.clarity.life.core.Block
import com.clarity.life.core.BlockItem
import com.clarity.life.core.TextCheck
import com.clarity.life.core.WorkingRules
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.Values
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.WorkingItemEventRow
import com.clarity.life.data.entity.WorkingItemRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * What the owner is working on (docs/CLARITY-V2-BUILD-SPEC.md sections 6 and 7). The memory keeper may propose
 * and update items; every rule is in [WorkingRules] (vector-tested): at most three active, a date only from an
 * explicit date in the owner's own words, a pause or drop only after two days or a tap, nothing from venting.
 * Every proposal starts unconfirmed. The keeper never deletes. Every change is an event with the owner's quote.
 */
class WorkingRepository(private val db: ClarityDatabase) {

    private val dao get() = db.working()

    // ---------- the WORKING_ON block ----------

    /**
     * One line per live item (contracts/context.md). An item quiet for 21 days gets one extra line, once, so the
     * coach can ask "still on this?" a single time; the question is recorded as asked.
     */
    suspend fun block(today: String, now: Long = System.currentTimeMillis()): Block {
        val items = dao.live()
        if (items.isEmpty()) return Block("WORKING_ON", emptyList())
        val lines = mutableListOf<BlockItem>()
        for (row in items) {
            lines.add(BlockItem(WorkingRules.contextLine(model(row), today), 0))
        }
        for (row in items) {
            if (row.status == "active" && WorkingRules.isStale(row.lastMentionedDay, today, row.askedStale == 1)) {
                lines.add(BlockItem("Quiet for a while, not asked yet: ${row.name}. Ask once whether they are still on it.", 0))
                dao.update(row.copy(askedStale = 1, updatedAt = now))
                dao.insertEvent(WorkingItemEventRow(itemId = row.id, kind = "asked_stale", day = today, createdAt = now))
            }
        }
        return Block("WORKING_ON", lines)
    }

    private fun model(r: WorkingItemRow) = WorkingRules.Item(
        name = r.name, status = r.status, confirmed = r.confirmed == 1, deadlineState = r.deadlineState,
        deadlineDate = r.deadlineDate, sizeText = r.sizeText, originQuote = r.originQuote, originDay = r.originDay,
        lastMentionedDay = r.lastMentionedDay,
    )

    // ---------- the owner's taps ----------

    /** That's right, on an unconfirmed item. */
    suspend fun confirm(id: Long, today: String, now: Long) {
        val row = dao.get(id) ?: return
        if (row.confirmed == 1) return
        // A tap also confirms an unclear deadline's date only when there is a date; there never is one without words.
        dao.update(row.copy(confirmed = 1, updatedAt = now))
        dao.insertEvent(WorkingItemEventRow(itemId = id, kind = "confirmed", day = today, createdAt = now))
    }

    /**
     * Not quite, then Save: the owner edits the name, status and deadline by hand. A date they type is theirs, so
     * it is confirmed; an empty date clears it. A fourth active item is refused (false).
     */
    suspend fun edit(id: Long, name: String, status: String, deadlineDate: String?, today: String, now: Long): Boolean {
        Values.requireValid("working_item.status", status)
        val row = dao.get(id) ?: return false
        if (status == "active" && row.status != "active" && dao.activeCount() >= WorkingRules.MAX_ACTIVE) return false
        val state = if (deadlineDate.isNullOrBlank()) "none" else "confirmed"
        dao.update(
            row.copy(
                name = name.trim().ifEmpty { row.name }, status = status, confirmed = 1,
                deadlineDate = deadlineDate?.takeIf { it.isNotBlank() }, deadlineState = state, updatedAt = now,
            ),
        )
        dao.insertEvent(WorkingItemEventRow(itemId = id, kind = "renamed", day = today, detail = "edited by hand", createdAt = now))
        return true
    }

    /** Delete for good (rule 5). */
    suspend fun delete(id: Long) = dao.delete(id)

    // ---------- the memory keeper ----------

    /** What was said after the last reflection, with the local day each message was written. */
    data class Said(val text: String, val day: String)

    /**
     * Applies the keeper's `working_on` and `venting` sections from the raw reflection JSON. Everything is checked
     * here: quotes must be the owner's own words, dates are read by code from those words, and the rules in
     * [WorkingRules] decide what may change. Returns how many changes were made. A malformed answer changes nothing.
     */
    suspend fun applyKeeper(raw: String, said: List<Said>, today: String, now: Long): Int {
        val root = parse(raw) ?: return 0
        val section = root["working_on"] as? JsonObject ?: return 0
        val texts = said.map { it.text }
        val venting = ((root["venting"] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { v ->
            (v as? JsonObject)?.str("quote")?.let { TextCheck.findHisQuote(it, texts) }?.let(::norm)
        }.toSet()
        fun isVenting(quote: String) = norm(quote).let { q -> venting.any { v -> v.contains(q) || q.contains(v) } }
        fun dayOf(quote: String): String =
            said.firstOrNull { TextCheck.findHisQuote(quote, listOf(it.text)) != null }?.day ?: today

        var changes = 0
        db.withTransaction {
            val existing = dao.all()
            // New items (at most two a conversation).
            for (p in list(section["propose"]).take(MAX_PROPOSALS)) {
                val name = p.str("name")?.trim().orEmpty().take(MAX_NAME)
                val quote = p.str("quote")?.let { TextCheck.findHisQuote(it, texts) }
                if (name.isEmpty() || quote == null || isVenting(quote)) continue
                val same = existing.firstOrNull { it.name.equals(name, ignoreCase = true) && it.status in LIVE }
                if (same != null) {
                    dao.update(same.copy(lastMentionedDay = today, updatedAt = now))
                    continue
                }
                val deadlineQuote = p.str("deadline_quote")?.let { TextCheck.findHisQuote(it, texts) }
                val outcome = WorkingRules.propose(dao.activeCount(), deadlineQuote, today)
                val id = dao.insert(
                    WorkingItemRow(
                        name = name, status = outcome.status, confirmed = outcome.confirmed,
                        deadlineDate = outcome.deadlineDate, deadlineState = outcome.deadlineState,
                        sizeText = p.str("size_text")?.takeIf { it.isNotBlank() }?.take(MAX_NAME),
                        originQuote = quote, originDay = dayOf(quote), lastMentionedDay = today,
                        createdAt = now, updatedAt = now,
                    ),
                )
                dao.insertEvent(WorkingItemEventRow(itemId = id, kind = "created", day = today, quote = quote, createdAt = now))
                changes++
            }
            // Changes to items they already have.
            for (u in list(section["update"])) {
                val row = u.long("id")?.let { dao.get(it) } ?: continue
                val change = u.str("change") ?: continue
                val quote = u.str("quote")?.let { TextCheck.findHisQuote(it, texts) } ?: continue
                val vent = isVenting(quote)
                val days = (dao.events(row.id).filter { it.kind == "status" && it.detail == "proposed:$change" }.map { it.day } + today).toSet().size
                if (!WorkingRules.changeAllowed(change, days, tapped = false, venting = vent, activeCount = dao.activeCount())) {
                    // A pause or drop said calmly on one day is remembered, so a second day can confirm it.
                    if (!vent && change in listOf("paused", "dropped") &&
                        dao.events(row.id).none { it.kind == "status" && it.detail == "proposed:$change" && it.day == today }
                    ) {
                        dao.insertEvent(WorkingItemEventRow(itemId = row.id, kind = "status", day = today, quote = quote, detail = "proposed:$change", createdAt = now))
                    }
                    continue
                }
                when (change) {
                    "paused", "dropped", "done", "active" -> {
                        dao.update(row.copy(status = change, lastMentionedDay = today, updatedAt = now))
                        dao.insertEvent(WorkingItemEventRow(itemId = row.id, kind = "status", day = today, quote = quote, detail = change, createdAt = now))
                        changes++
                    }
                    "deadline" -> {
                        val dq = u.str("deadline_quote")?.let { TextCheck.findHisQuote(it, texts) } ?: quote
                        val date = WorkingRules.explicitDate(dq, today)
                        dao.update(
                            row.copy(
                                deadlineDate = date, deadlineState = if (date != null) "confirmed" else "unclear",
                                lastMentionedDay = today, updatedAt = now,
                            ),
                        )
                        dao.insertEvent(WorkingItemEventRow(itemId = row.id, kind = "deadline", day = today, quote = dq, detail = date ?: "unclear", createdAt = now))
                        changes++
                    }
                    "progress" -> {
                        val text = u.str("progress_text")?.trim().orEmpty().take(MAX_NAME)
                        if (text.isNotEmpty()) {
                            dao.update(row.copy(lastMentionedDay = today, updatedAt = now))
                            dao.insertEvent(WorkingItemEventRow(itemId = row.id, kind = "progress", day = today, quote = quote, detail = text, createdAt = now))
                            changes++
                        }
                    }
                }
            }
            // Mentioned: keeps the list fresh.
            for (id in list(section["mentioned"], objects = false)) {
                val row = id.long("id") ?: continue
                dao.get(row)?.let { if (it.lastMentionedDay != today) dao.update(it.copy(lastMentionedDay = today, updatedAt = now)) }
            }
        }
        if (changes > 0) db.diagnostics().log(ErrorLogRow(at = now, area = "working", code = "keeper_changes", detail = "n=$changes"))
        return changes
    }

    // ---------- small JSON helpers (the keeper's answer is untrusted) ----------

    private fun parse(raw: String): JsonObject? = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) null else Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

    /** The items of a list; for "mentioned" (plain ids) each id is wrapped as {"id": n}. */
    private fun list(e: JsonElement?, objects: Boolean = true): List<JsonObject> {
        val arr = (e as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            if (objects) el as? JsonObject else (el as? JsonPrimitive)?.contentOrNull?.let { JsonObject(mapOf("id" to JsonPrimitive(it))) }
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()

    companion object {
        private val LIVE = setOf("active", "paused")
        private const val MAX_PROPOSALS = 2
        private const val MAX_NAME = 80
    }
}
