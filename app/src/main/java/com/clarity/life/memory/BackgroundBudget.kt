package com.clarity.life.memory

import androidx.room.withTransaction
import com.clarity.life.core.Planner
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.settings.SettingsRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hard cap on background AI calls (docs/CLARITY-V2-BUILD-SPEC.md section 5): at most [CAP] a day, counted by
 * the local day (it turns over at the day boundary, 04:00 by default). Memory extraction, letters, tidy-ups and
 * every retry take from the same number. It is not a setting on purpose: it is the owner's wallet guard. When it
 * is used up, background work stops until the next day and Settings says so. Chat is never limited.
 */
@Singleton
class BackgroundBudget @Inject constructor(
    private val db: ClarityDatabase,
    private val settings: SettingsRepository,
) {
    /** Takes one call from today's budget. False when today's budget is spent (nothing is taken then). */
    suspend fun take(now: Long = System.currentTimeMillis()): Boolean {
        val day = dayOf(now)
        return db.withTransaction {
            val dao = db.working()
            dao.ensureDay(day)
            val used = dao.usageDay(day)?.bgCalls ?: 0L
            if (used >= CAP) {
                false
            } else {
                dao.addBackgroundCall(day)
                true
            }
        }
    }

    /** True when today's background calls are used up (Settings shows "Background work paused until tomorrow"). */
    suspend fun paused(now: Long = System.currentTimeMillis()): Boolean =
        (db.working().usageDay(dayOf(now))?.bgCalls ?: 0L) >= CAP

    /** Chat messages are only counted, for Health. They are never limited. */
    suspend fun countChatMessage(now: Long = System.currentTimeMillis()) {
        val day = dayOf(now)
        db.working().ensureDay(day)
        db.working().addChatMessage(day)
    }

    private suspend fun dayOf(now: Long): String =
        Planner.dayKey(Instant.ofEpochMilli(now).toString(), settings.timeZone(), settings.dayBoundaryHour()).toString()

    companion object {
        const val CAP = 12
    }
}
