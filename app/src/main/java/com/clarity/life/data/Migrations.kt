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

    /**
     * Version 8 (Max plan Phase 6): target_version, a target's values from a day on (what the plan asked for on
     * any day, and when it changed), and target.minutes, his estimate of a session's minutes for a day's load.
     * Only adds. Old targets get no version rows: they are read as their current values from their start day
     * (nothing is guessed about changes made before), and get that row the first time they change.
     */
    val V7_TO_V8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `target` ADD COLUMN `minutes` REAL")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `target_version` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`target_id` INTEGER NOT NULL, `from_day` TEXT NOT NULL, `minimum` REAL NOT NULL, " +
                    "`target` REAL NOT NULL, `schedule` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, FOREIGN KEY(`target_id`) REFERENCES `target`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `idx_target_version_day` ON `target_version` (`target_id`, `from_day`)",
            )
        }
    }

    /**
     * Version 9 (C1): usage_month.by_job_json, tracking tokens and cache share by job (chat, card_followup,
     * memory, letter, review ...). Only adds a column; old rows keep the default '{}'.
     */
    val V8_TO_V9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `usage_month` ADD COLUMN `by_job_json` TEXT NOT NULL DEFAULT '{}'")
        }
    }

    /**
     * Version 10 (Clarity v2): message.status may be 'pending' (waiting to send, no signal), the daily counter
     * (usage_day) and what they are working on (working_item and working_item_event). The CHECK on
     * message.status cannot be changed in place, so the table is copied with its rows and ids; the card rows
     * that point at message ids keep pointing at the same ids (foreign keys are not enforced while a
     * migration runs, and nothing is deleted).
     */
    val V9_TO_V10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE `message_v10` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`conversation_id` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `status` TEXT NOT NULL DEFAULT 'complete', `model` TEXT, " +
                    "`tokens_in` INTEGER, `tokens_out` INTEGER, " +
                    "FOREIGN KEY(`conversation_id`) REFERENCES `conversation`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL(
                "INSERT INTO `message_v10` (`id`, `conversation_id`, `role`, `content`, `created_at`, `status`, " +
                    "`model`, `tokens_in`, `tokens_out`) SELECT `id`, `conversation_id`, `role`, `content`, " +
                    "`created_at`, `status`, `model`, `tokens_in`, `tokens_out` FROM `message`",
            )
            db.execSQL("DROP TABLE `message`")
            db.execSQL("ALTER TABLE `message_v10` RENAME TO `message`")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_message_conv` ON `message` (`conversation_id`, `id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_message_status` ON `message` (`status`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `usage_day` (`day` TEXT NOT NULL, `bg_calls` INTEGER NOT NULL DEFAULT 0, " +
                    "`chat_messages` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`day`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `working_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `status` TEXT NOT NULL DEFAULT 'active', `confirmed` INTEGER NOT NULL DEFAULT 0, " +
                    "`deadline_date` TEXT, `deadline_state` TEXT NOT NULL DEFAULT 'none', `size_text` TEXT, " +
                    "`origin_quote` TEXT NOT NULL, `origin_day` TEXT NOT NULL, `last_mentioned_day` TEXT NOT NULL, " +
                    "`asked_stale` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_working_item_status` ON `working_item` (`status`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `working_item_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`item_id` INTEGER NOT NULL, `kind` TEXT NOT NULL, `day` TEXT NOT NULL, " +
                    "`quote` TEXT NOT NULL DEFAULT '', `detail` TEXT NOT NULL DEFAULT '', `created_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`item_id`) REFERENCES `working_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_working_item_event_item` ON `working_item_event` (`item_id`, `id`)")
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
