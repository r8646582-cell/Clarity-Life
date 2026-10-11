package com.clarity.life.promise

import androidx.room.withTransaction
import com.clarity.life.core.PromiseText
import com.clarity.life.core.TimeFacts
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.StateMachines
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.memory.SearchIndex
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * ResolvePromise and its siblings: the one writer for a promise after it was saved from a card (spec
 * section 18, point 2). Each command is his tap, one transaction, a move the promise state machine allows
 * (contracts/states.json), and then the reminder is brought in line with the row (cancelled once answered).
 */
@Singleton
class PromiseRepository @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
    private val reminders: Reminders,
    private val search: SearchIndex,
) {
    private val dao get() = db.promises()

    fun watchOpen(): Flow<List<PromiseRow>> = dao.watchOpen()

    fun watchPast(limit: Int = 30): Flow<List<PromiseRow>> = dao.watchPast(limit)

    /** Done. */
    suspend fun done(id: Long, now: Long) = answer(id, "kept", null, now)

    /** Not done, with the reason chip he picked (or none). A reason is never invented for him. */
    suspend fun notDone(id: Long, reason: String?, now: Long) {
        require(reason == null || reason in PromiseText.REASONS) { "unknown reason" }
        answer(id, "not_done", reason, now)
    }

    /** Let go: he decided it no longer matters. Kept in the past list with that outcome. */
    suspend fun letGo(id: Long, now: Long) = answer(id, "let_go", null, now)

    /**
     * Moved: the promise keeps its history as "moved", and the same promise starts again, open, at the moment
     * he picked (never in the past), with the same reminder choice. Returns the new promise's id.
     */
    suspend fun move(id: Long, to: Instant, now: Long): Long? {
        if (to.toEpochMilli() <= now) return null
        val tz = settings.timeZone()
        val newId = db.withTransaction {
            val p = dao.get(id) ?: return@withTransaction null
            if (p.status != "open") return@withTransaction null
            dao.resolve(id, StateMachines.move(StateMachines.PROMISE, p.status, "moved"), null, now)
            StateMachines.requireStart(StateMachines.PROMISE, "open")
            val created = dao.insert(
                p.copy(
                    id = 0,
                    dueAt = to.toEpochMilli(),
                    dueText = TimeFacts.cardTime(to, tz),
                    status = "open",
                    reason = null,
                    createdAt = now,
                    resolvedAt = null,
                ),
            )
            dao.get(id)?.let { search.indexPromise(it) }
            dao.get(created)?.let { search.indexPromise(it) }
            created
        } ?: return null
        reminders.sync(id, now)
        reminders.sync(newId, now)
        return newId
    }

    /** Undo an answer (Done, Not done, Let go): back to open. A move cannot be undone; the new one stays. */
    suspend fun undo(id: Long, now: Long): Boolean {
        val ok = db.withTransaction {
            val p = dao.get(id) ?: return@withTransaction false
            if (!StateMachines.PROMISE.allows(p.status, "open")) return@withTransaction false
            dao.resolve(id, StateMachines.move(StateMachines.PROMISE, p.status, "open"), null, null)
            dao.get(id)?.let { search.indexPromise(it) }
            true
        }
        if (ok) reminders.sync(id, now)
        return ok
    }

    private suspend fun answer(id: Long, to: String, reason: String?, now: Long): Boolean {
        val ok = db.withTransaction {
            val p = dao.get(id) ?: return@withTransaction false
            if (p.status != "open") return@withTransaction false
            dao.resolve(id, StateMachines.move(StateMachines.PROMISE, p.status, to), reason, now)
            dao.get(id)?.let { search.indexPromise(it) }
            true
        }
        if (ok) reminders.sync(id, now)
        return ok
    }

    companion object {
        fun model(p: PromiseRow) =
            PromiseText.Promise(p.id, p.text, Instant.ofEpochMilli(p.dueAt), p.status, p.reason, p.remind == 1)
    }
}
