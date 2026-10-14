package com.clarity.life.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Every schema change ever made, as written SQL (CLAUDE.md rule 8). A migration only ever adds: no table or
 * column is dropped, and no row is touched. Each one has a test in MigrationTest that migrates a database
 * with rows in it and checks they survived.
 *
 * The SQL is exactly what Room exports for the entity (app/schemas/.../N.json), because Room compares the
 * migrated tables with the entities when the database opens and refuses to open on any difference.
 */
object Migrations {

    /** Version 2 (stage B): reflection_item, for the idea tags, disagreements and contradictions. */
    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `reflection_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`conversation_id` INTEGER, `kind` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                    "`his_words` TEXT NOT NULL DEFAULT '', `day` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`conversation_id`) REFERENCES `conversation`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `idx_reflection_item_kind` ON `reflection_item` (`kind`, `id`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reflection_item_conversation_id` " +
                    "ON `reflection_item` (`conversation_id`)",
            )
        }
    }

    /**
     * Version 3: backlog.start_day, his local creation day stored at write time. Existing rows keep NULL and
     * get their day once, from the settings of the moment, the first time the planner reads them.
     */
    val V2_TO_V3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `backlog` ADD COLUMN `start_day` TEXT")
        }
    }

    /** Version 4: story, the monthly story job's text for Your story (spec 2.5). Only adds a table. */
    val V3_TO_V4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `story` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`period_start` TEXT NOT NULL, `period_end` TEXT NOT NULL, `short` TEXT NOT NULL, " +
                    "`then_quote_id` INTEGER, `now_quote_id` INTEGER, " +
                    "`overcame_json` TEXT NOT NULL DEFAULT '[]', `created_at` INTEGER NOT NULL, `key` TEXT NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_story_key` ON `story` (`key`)")
        }
    }

    /** Version 5: brief, the morning brief (spec section 8, the 0.1.6 redesign). Only adds a table. */
    val V4_TO_V5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `brief` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`day_key` TEXT NOT NULL, `body` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `key` TEXT NOT NULL)",
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_brief_key` ON `brief` (`key`)")
        }
    }

    /**
     * Version 6 (Max plan Phase 3): facts with time. profile_entry.since_day (the day a value was first said),
     * profile_past (values replaced by newer ones), and checkin.entered_minute and entered_day (his local time
     * and day when he answered). Only adds; old rows keep NULL, which means "not known", never a guess.
     */
    val V5_TO_V6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `profile_entry` ADD COLUMN `since_day` TEXT")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `profile_past` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`key` TEXT NOT NULL, `value` TEXT NOT NULL, `since_day` TEXT, `until_day` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_profile_past_key` ON `profile_past` (`key`, `id`)")
            db.execSQL("ALTER TABLE `checkin` ADD COLUMN `entered_minute` INTEGER")
            db.execSQL("ALTER TABLE `checkin` ADD COLUMN `entered_day` TEXT")
        }
    }

    /**
     * Max plan Phase 5: indexes only, for the queries the ten-year database showed reading a whole table
     * (tools/query_plans.py). No row and no column changes; building them reads each table once.
     */
    val V6_TO_V7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            for (sql in V7_INDEXES) db.execSQL(sql)
        }
    }

    val V7_INDEXES = listOf(
        "CREATE INDEX IF NOT EXISTS `idx_conversation_started` ON `conversation` (`started_at`)",
        "CREATE INDEX IF NOT EXISTS `idx_message_status` ON `message` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_promise_due` ON `promise` (`due_at`)",
        "CREATE INDEX IF NOT EXISTS `idx_quote_day` ON `quote` (`said_day`, `id`)",
        "CREATE INDEX IF NOT EXISTS `idx_job_status` ON `job` (`status`)",
        "CREATE INDEX IF NOT EXISTS `idx_error_at` ON `error_log` (`at`)",
    )
}
