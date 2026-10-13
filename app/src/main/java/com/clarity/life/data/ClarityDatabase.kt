package com.clarity.life.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.clarity.life.data.dao.CardDao
import com.clarity.life.data.dao.ConversationDao
import com.clarity.life.data.dao.DiagnosticsDao
import com.clarity.life.data.dao.JobDao
import com.clarity.life.data.dao.EmbeddingDao
import com.clarity.life.data.dao.MemoryDao
import com.clarity.life.data.dao.SearchDao
import com.clarity.life.data.dao.MessageDao
import com.clarity.life.data.dao.PromptOverrideDao
import com.clarity.life.data.dao.RepairDao
import com.clarity.life.data.dao.SettingsDao
import com.clarity.life.data.entity.BacklogRow
import com.clarity.life.data.entity.CardRow
import com.clarity.life.data.entity.ChapterRow
import com.clarity.life.data.entity.CheckinRow
import com.clarity.life.data.entity.ConversationRow
import com.clarity.life.data.entity.EmbeddingRow
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.EventRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.data.entity.GoalRevisionRow
import com.clarity.life.data.entity.GoalRow
import com.clarity.life.data.entity.JobRow
import com.clarity.life.data.entity.LetterRow
import com.clarity.life.data.entity.MessageRow
import com.clarity.life.data.entity.NoteEvidenceRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.PersonRow
import com.clarity.life.data.entity.PlanRow
import com.clarity.life.data.entity.ProfileEntryRow
import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.data.entity.PromptOverrideRow
import com.clarity.life.data.entity.QuoteRow
import com.clarity.life.data.entity.ReflectionItemRow
import com.clarity.life.data.entity.RestDayRow
import com.clarity.life.data.entity.ReviewRow
import com.clarity.life.data.entity.SearchDocRow
import com.clarity.life.data.entity.SettingRow
import com.clarity.life.data.entity.TargetRow
import com.clarity.life.data.entity.UsageMonthRow

/**
 * The whole database, version 4, exactly the 30 tables of db/schema.sql.
 *
 * Rules that never break here (CLAUDE.md rule 8): the schema is exported and committed, and every future
 * change is a written migration plus a MigrationTest. The destructive-migration escape hatch Room offers is
 * never used anywhere in this project, and tools/forbidden_patterns.sh greps for its name on every push. A
 * failed migration stops with an error; it never wipes his years of data.
 */
@Database(
    version = 4,
    exportSchema = true,
    entities = [
        // chat
        FocusRow::class, ConversationRow::class, MessageRow::class, CardRow::class,
        // planner
        GoalRow::class, GoalRevisionRow::class, PlanRow::class, BacklogRow::class, TargetRow::class,
        RestDayRow::class, CheckinRow::class, PromiseRow::class,
        // memory and writing
        NoteRow::class, NoteEvidenceRow::class, ProfileEntryRow::class, PersonRow::class, EventRow::class,
        ReviewRow::class, ChapterRow::class, LetterRow::class, StoryRow::class, QuoteRow::class,
        SearchDocRow::class, EmbeddingRow::class, ReflectionItemRow::class,
        // housekeeping
        SettingRow::class, PromptOverrideRow::class, ErrorLogRow::class, UsageMonthRow::class, JobRow::class,
    ],
)
abstract class ClarityDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao
    abstract fun cards(): CardDao
    abstract fun promises(): com.clarity.life.data.dao.PromiseDao
    abstract fun planner(): com.clarity.life.data.dao.PlannerDao
    abstract fun writing(): com.clarity.life.data.dao.WritingDao
    abstract fun promptOverrides(): PromptOverrideDao
    abstract fun diagnostics(): DiagnosticsDao
    abstract fun jobs(): JobDao
    abstract fun repair(): RepairDao
    abstract fun memory(): MemoryDao
    abstract fun search(): SearchDao
    abstract fun embeddings(): EmbeddingDao

    companion object {
        const val NAME = "clarity.db"

        /** Every migration ever written goes in this list. The list is never allowed to be "skip it". */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            Migrations.V1_TO_V2,
            Migrations.V2_TO_V3,
            Migrations.V3_TO_V4,
        )
    }
}
