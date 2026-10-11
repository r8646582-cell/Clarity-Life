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
}
