package com.clarity.life.chat

import androidx.room.withTransaction
import com.clarity.life.core.Card
import com.clarity.life.core.CardParser
import com.clarity.life.core.KnownIds
import com.clarity.life.core.PlanApply
import com.clarity.life.core.PlanBlocks
import com.clarity.life.core.PlanDates
import com.clarity.life.core.Planner
import com.clarity.life.core.StrictJson
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.StateMachines
import com.clarity.life.data.Values
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SaveCard: the only way a card the coach offered reaches the rest of the database (spec section 18, point 2).
 * Each command runs only because the owner tapped the card's button, and is one transaction:
 * 1. the card must still be `offered` (a second tap, or a tap on a card he already dismissed, saves nothing);
 * 2. the stored card is validated again with the real parser, so nothing reaches a table unchecked;
 * 3. the row is written, and the card becomes `saved` with [CardRow.savedRef] naming the real row
 *    ("promise:12"), which is what the card shows as Saved.
 *
 * Plan, target change and backlog cards are worked out first ([planPreview]: exactly what a tap would write, shown
 * on the card), and the save runs those same steps.
 */
@Singleton
class CardSaver @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val search: com.clarity.life.memory.SearchIndex,
    private val planner: com.clarity.life.planner.PlannerRepository,
    private val reminders: com.clarity.life.promise.Reminders,
) {

    sealed interface Result {
        data class Saved(val ref: String) : Result

        /** Nothing was written; [why] is a short code for the log and the quiet line on the card. */
        data class NotSaved(val why: String) : Result
    }

    /**
     * Set it, on a promise card, for the moment he saw on the card or chose in the time picker. The moment
     * must still be ahead: a promise is never saved in the past.
     */
    suspend fun savePromise(cardId: Long, at: Instant, now: Long): Result = decide(cardId, "promise", now) { row, card ->
        if (at.toEpochMilli() <= now) return@decide Result.NotSaved("past")
        StateMachines.requireStart(StateMachines.PROMISE, "open")
        val tz = settings.timeZone()
        val conversationId = db.messages().get(row.messageId)?.conversationId
        val id = db.promises().insert(
            PromiseRow(
                text = card.str("text")!!,
                why = card.str("why").orEmpty(),
                dueAt = at.toEpochMilli(),
                dueText = TimeFacts.cardTime(at, tz),
                remind = if (card.bool("remind") == true) 1 else 0,
                status = "open",
                sourceConversationId = conversationId,
                createdAt = now,
            ),
        )
        // Findable later ("did I keep...") once it has left OPEN_PROMISES.
        db.promises().get(id)?.let { search.indexPromise(it) }
        Result.Saved("promise:$id")
    }

    /**
     * Keep it, on a note card. The note is his, because he tapped it: confirmed from the start, so no
     * background job may ever change it. It has no evidence row, so forgetting the conversation it came
     * from leaves it alone (only notes learned from a conversation go with it).
     */
    suspend fun saveNote(cardId: Long, now: Long): Result = decide(cardId, "note", now) { _, card ->
        val kind = card.str("kind")!!
        Values.requireValid("note.kind", kind)
        StateMachines.requireStart(StateMachines.NOTE, "active")
        val today = Planner.dayKey(
            Instant.ofEpochMilli(now).toString(),
            settings.timeZone(),
            settings.dayBoundaryHour(),
        ).toString()
        val id = db.memory().insertNote(
            NoteRow(
                kind = kind,
                text = card.str("text")!!,
                confidence = "confirmed",
                firstSeen = today,
                lastSeen = today,
                createdAt = now,
            ),
        )
        Result.Saved("note:$id")
    }

    /**
     * Start it, on a focus card: a new active focus with his reason, and the conversation it came up in
     * becomes its first one, so the next reply already has the FOCUS block and the focus summary starts here.
     */
    suspend fun saveFocus(cardId: Long, now: Long): Result = decide(cardId, "focus", now) { row, card ->
        val id = db.promises().insertFocus(
            FocusRow(name = card.str("name")!!, why = card.str("why")!!, createdAt = now, updatedAt = now),
        )
        val conversation = db.messages().get(row.messageId)?.conversationId?.let { db.conversations().get(it) }
        if (conversation != null && conversation.focusId == null) {
            db.conversations().update(conversation.copy(focusId = id))
            db.conversations().setPrefix(conversation.id, null, null)
        }
        Result.Saved("focus:$id")
    }

    /**
     * Switch, on a mode card: the conversation follows the new chat type from the next reply on. The coach
     * never switches by itself; this runs only on his tap.
     */
    suspend fun switchMode(cardId: Long, now: Long): Result = decide(cardId, "mode", now) { row, card ->
        val to = card.str("to")!!
        val conversationId = db.messages().get(row.messageId)?.conversationId
            ?: return@decide Result.NotSaved("gone")
        val conversation = db.conversations().get(conversationId) ?: return@decide Result.NotSaved("gone")
        if (to == "focus") {
            // Into a focus: the chat type (and so the cards allowed) stays; the conversation joins the focus.
            val focusId = card.str("focus")?.removePrefix("F")?.toLongOrNull()
                ?: return@decide Result.NotSaved("unsupported")
            val focus = db.memory().focus(focusId)
            if (focus == null || focus.status == "done") return@decide Result.NotSaved("gone")
            db.conversations().update(conversation.copy(focusId = focusId))
            db.conversations().setPrefix(conversationId, null, null)
            return@decide Result.Saved("mode:focus:$focusId")
        }
        if (to !in CardParser.CHAT_TYPES) return@decide Result.NotSaved("unsupported")
        Values.requireValid("conversation.chat_type", to)
        db.conversations().update(conversation.copy(chatType = to, focusId = null))
        // The chat-type file and the allowed card list are part of the prefix, so it is rebuilt.
        db.conversations().setPrefix(conversationId, null, null)
        Result.Saved("mode:$to")
    }

    /**
     * Log: on a checkin card ("Log: Tax lectures, 3 today").
     */
    suspend fun saveCheckin(cardId: Long, now: Long): Result = decide(cardId, "checkin", now) { _, card ->
        val targetId = card.str("target")?.removePrefix("T")?.toLongOrNull()
            ?: return@decide Result.NotSaved("target gone")
        val target = db.planner().target(targetId) ?: return@decide Result.NotSaved("target gone")
        if (target.status != "active") return@decide Result.NotSaved("target ended")
        val amount = card.num("amount") ?: return@decide Result.NotSaved("invalid")
        val today = dayOf(now)
        val level = try {
            Planner.levelFor(amount, target.minimum, target.target)
        } catch (e: Exception) {
            return@decide Result.NotSaved("refused")
        }
        val reason = card.str("reason")
        planner.checkIn(
            targetId = targetId,
            day = today,
            today = today,
            level = level,
            amount = amount,
            reason = reason,
            note = null,
            now = now,
        )
        Result.Saved("checkin:$targetId")
    }

    /**
     * Done or Not done: on a promise_status card.
     */
    suspend fun savePromiseStatus(cardId: Long, chosenStatus: String? = null, now: Long): Result = decide(cardId, "promise_status", now) { _, card ->
        val promiseId = card.str("promise")?.removePrefix("P")?.toLongOrNull()
            ?: return@decide Result.NotSaved("gone")
        val p = db.promises().get(promiseId) ?: return@decide Result.NotSaved("gone")
        if (p.status != "open") return@decide Result.NotSaved("already_${p.status}")
        val st = chosenStatus ?: card.str("status") ?: return@decide Result.NotSaved("invalid")
        val dbStatus = when (st) {
            "done" -> "kept"
            "not_done" -> "not_done"
            "let_go" -> "let_go"
            else -> return@decide Result.NotSaved("unsupported")
        }
        if (!StateMachines.PROMISE.allows(p.status, dbStatus)) return@decide Result.NotSaved("unsupported")
        val reason = if (dbStatus == "not_done") card.str("reason") else null
        db.promises().resolve(promiseId, StateMachines.move(StateMachines.PROMISE, p.status, dbStatus), reason, now)
        db.promises().get(promiseId)?.let { search.indexPromise(it) }
        reminders.sync(promiseId, now)
        Result.Saved("promise_status:$promiseId")
    }

    suspend fun targetName(targetId: Long): String? = db.planner().target(targetId)?.name

    suspend fun promiseText(promiseId: Long): String? = db.promises().get(promiseId)?.text

    // ---------- plan, target change and backlog ----------

    /** What a plan, target change or backlog card would write now, or why it cannot be saved. */
    sealed interface Preview {
        data class Ready(val lines: List<String>) : Preview
        data class Blocked(val why: String) : Preview
    }

    private sealed interface PlanWork {
        data class Plan(val steps: PlanApply.Steps) : PlanWork
        data class Target(val step: PlanApply.TargetStep.Change) : PlanWork
        data class Backlog(val step: PlanApply.BacklogStep) : PlanWork
    }

    /**
     * The card against the tracker as it is now. Dates are read against the day the coach offered the card (as
     * a promise card's "when" is), so "tomorrow" keeps meaning the day he heard; a review day that has passed by
     * the time he taps saves nothing.
     */
    private suspend fun work(row: CardRow, card: Card, now: Long): PlanWork {
        val offeredDay = dayOf(row.createdAt)
        val days = when (val c = PlanDates.checkCard(card, offeredDay)) {
            is PlanDates.Checked.Bad -> throw PlanApply.Refused(if (c.why.startsWith("unreadable")) "date" else c.why)
            is PlanDates.Checked.Ok -> c.days
        }
        return when (card.type) {
            "plan" -> {
                if (days.review != null && !days.review.isAfter(dayOf(now))) throw PlanApply.Refused("review passed")
                PlanWork.Plan(PlanApply.plan(card, days, planner.activeGoalIds(), planner.oldTargets(), planner.oldBacklogs()))
            }
            "target_change" -> PlanWork.Target(PlanApply.targetChange(card, days, planner.oldTargets()))
            "backlog" -> PlanWork.Backlog(PlanApply.backlog(card, days, planner.oldBacklogs()))
            else -> throw PlanApply.Refused("wrong_type")
        }
    }

    suspend fun planPreview(row: CardRow, now: Long): Preview {
        if (row.type !in PLAN_TYPES) return Preview.Blocked("wrong_type")
        val card = readCard(row, now) ?: return Preview.Blocked("invalid")
        return try {
            when (val w = work(row, card, now)) {
                is PlanWork.Plan -> Preview.Ready(w.steps.lines())
                is PlanWork.Target -> Preview.Ready(listOf(PlanApply.changeLine(w.step)))
                is PlanWork.Backlog -> Preview.Ready(PlanApply.Steps(null, null, "", null, listOf(w.step), emptyList()).lines())
            }
        } catch (e: PlanApply.Refused) {
            Preview.Blocked(e.why)
        }
    }

    /** Save plan, Change it or Save: the steps the card showed, in one transaction. */
    suspend fun savePlanCard(cardId: Long, now: Long): Result {
        val type = db.cards().get(cardId)?.type ?: return Result.NotSaved("gone")
        if (type !in PLAN_TYPES) return Result.NotSaved("wrong_type")
        // A refusal from the database rules (a minimum above a target, say) escapes the transaction, so nothing
        // half-written stays; it becomes a quiet line on the card.
        return try {
            savePlanSteps(cardId, type, now)
        } catch (e: IllegalArgumentException) {
            db.diagnostics().log(ErrorLogRow(at = now, area = "card", code = "not_saved_refused", detail = type))
            Result.NotSaved("refused")
        }
    }

    private suspend fun savePlanSteps(cardId: Long, type: String, now: Long): Result {
        return decide(cardId, type, now) { row, card ->
            val w = try {
                work(row, card, now)
            } catch (e: PlanApply.Refused) {
                return@decide Result.NotSaved(e.why)
            }
            val conversationId = db.messages().get(row.messageId)?.conversationId
            when (w) {
                is PlanWork.Plan -> Result.Saved("plan:" + planner.applyPlan(w.steps, conversationId, now))
                is PlanWork.Target -> Result.Saved("target:" + planner.applyTargetChange(w.step, now))
                is PlanWork.Backlog -> Result.Saved("backlog:" + planner.applyBacklog(w.step, null, now))
            }
        }
    }

    private suspend fun dayOf(at: Long) = Planner.dayKey(
        Instant.ofEpochMilli(at).toString(),
        settings.timeZone(),
        settings.dayBoundaryHour(),
    )

    /** Not now: a real tap, so the card really is dismissed, and CARD_RESULTS tells the coach. */
    suspend fun dismiss(cardId: Long, now: Long): Boolean = db.withTransaction {
        val row = db.cards().get(cardId) ?: return@withTransaction false
        if (row.status != "offered") return@withTransaction false
        db.cards().decide(cardId, StateMachines.move(StateMachines.CARD, row.status, "dismissed"), null, now)
        true
    }

    /**
     * The Saved receipt under a card, built from the row the tap really wrote (never from what the model
     * said). Null when the card is not saved, or the row has gone since (a forgotten note, for example).
     */
    suspend fun receipt(row: CardRow): String? {
        if (row.status != "saved") return null
        val ref = row.savedRef ?: return null
        val kind = ref.substringBefore(':')
        val id = ref.substringAfter(':').toLongOrNull()
        return when (kind) {
            "promise" -> db.promises().get(id ?: return null)?.let { p ->
                "${p.text} · ${p.dueText} · " + if (p.remind == 1) "reminder on" else "no reminder"
            }
            "note" -> db.memory().note(id ?: return null)?.takeIf { it.status == "active" }?.let {
                "In What I know: ${it.text}"
            }
            "focus" -> db.memory().focus(id ?: return null)?.let { "New focus: ${it.name} · this conversation is in it" }
            "mode" -> {
                val to = ref.substringAfter(':')
                if (to.startsWith("focus:")) {
                    db.memory().focus(to.substringAfter(':').toLongOrNull() ?: return null)
                        ?.let { "This conversation is now in your focus ${it.name}." }
                } else {
                    "This conversation is now ${CHAT_TYPE_NAMES[to] ?: "switched"}."
                }
            }
            "plan" -> db.planner().plan(id ?: return null)?.let { plan ->
                val goal = db.planner().goal(plan.goalId)?.title ?: return null
                "Plan v${plan.version} for $goal · review ${TimeFacts.shortDay(java.time.LocalDate.parse(plan.reviewDay))} · in Plan, Goals"
            }
            "target" -> db.planner().target(id ?: return null)?.let { t ->
                val u = if (t.unit.isBlank()) "" else " ${t.unit}"
                "${t.name}: target ${PlanApply.n(t.target)}$u, ${t.schedule}" +
                    (if (t.minimum > 0) ", minimum ${PlanApply.n(t.minimum)}" else "") +
                    (t.endDay?.let { ", until ${TimeFacts.shortDay(java.time.LocalDate.parse(it))}" } ?: "")
            }
            "backlog" -> db.planner().backlog(id ?: return null)?.let { b ->
                "${b.name}: ${PlanApply.n(b.total)} ${b.unit} by ${TimeFacts.shortDay(java.time.LocalDate.parse(b.deadline))}"
            }
            "checkin" -> db.planner().target(id ?: return null)?.let { t ->
                val u = if (t.unit.isBlank()) "" else " ${t.unit}"
                val chk = db.planner().checkin(t.id, dayOf(row.decidedAt ?: row.createdAt).toString())
                val amountStr = chk?.let { PlanApply.n(it.amount) } ?: ""
                "Logged: ${t.name}, $amountStr$u today"
            }
            "promise_status" -> db.promises().get(id ?: return null)?.let { p ->
                "Promise: ${p.text} · ${com.clarity.life.core.PromiseText.outcome(com.clarity.life.promise.PromiseRepository.model(p))}"
            }
            else -> null
        }
    }

    /** The shared shape of every SaveCard command: check, write, mark saved, all in one transaction. */
    private suspend fun decide(
        cardId: Long,
        type: String,
        now: Long,
        write: suspend (CardRow, Card) -> Result,
    ): Result {
        val result = db.withTransaction {
            val row = db.cards().get(cardId) ?: return@withTransaction Result.NotSaved("gone")
            if (row.type != type) return@withTransaction Result.NotSaved("wrong_type")
            if (row.status != "offered") return@withTransaction Result.NotSaved("already_${row.status}")
            val card = readCard(row, now) ?: return@withTransaction Result.NotSaved("invalid")
            val written = write(row, card)
            if (written is Result.Saved) {
                db.cards().decide(cardId, StateMachines.move(StateMachines.CARD, row.status, "saved"), written.ref, now)
            }
            written
        }
        if (result is Result.NotSaved) {
            // A code only: a log line never carries a word of what was said.
            db.diagnostics().log(ErrorLogRow(at = now, area = "card", code = "not_saved_${result.why}", detail = type))
        }
        return result
    }

    companion object {
        val CHAT_TYPE_NAMES = mapOf(
            "free" to "Free talk",
            "untangle" to "Untangle",
            "decide" to "Decide",
            "plan" to "Plan and track",
        )

        val PLAN_TYPES = setOf("plan", "target_change", "backlog")
    }

    /**
     * The stored card, validated again with the real parser against the ids that exist now (a goal archived
     * since the coach offered it fails here). The review date was checked against the options the app offered
     * when the card arrived; PlanDates checks it again. A mode card into a focus needs a focus that is not done.
     */
    private suspend fun readCard(row: CardRow, now: Long): Card? = try {
        val raw = StrictJson.parse(row.payloadJson)
        val known = if (row.type in PLAN_TYPES || row.type == "checkin") {
            val review = raw.asObj?.map?.get("review_date")?.asStr
            PlanBlocks.known(planner.tracker(dayOf(now)), dayOf(now))
                .copy(reviewOptions = setOfNotNull(review))
        } else if (row.type == "promise_status") {
            KnownIds(promises = db.promises().open().map { "P${it.id}" }.toSet())
        } else if (row.type == "mode") {
            KnownIds(focuses = db.memory().openFocusIds().map { "F$it" }.toSet())
        } else {
            KnownIds()
        }
        CardParser.validate(raw, known).takeIf { it.type == row.type }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
