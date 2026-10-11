package com.clarity.life.data

import android.content.Context
import androidx.room.Room
import com.clarity.life.data.dao.CardDao
import com.clarity.life.data.dao.ConversationDao
import com.clarity.life.data.dao.DiagnosticsDao
import com.clarity.life.data.dao.JobDao
import com.clarity.life.data.dao.MemoryDao
import com.clarity.life.data.dao.MessageDao
import com.clarity.life.data.dao.PromptOverrideDao
import com.clarity.life.data.dao.RepairDao
import com.clarity.life.data.dao.SettingsDao
import com.clarity.life.security.SecretStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the one encrypted database. The key is 32 random bytes held in the Android Keystore
 * ([SecretStore]); SQLCipher never sees a passphrase the owner has to remember, and the file on disk is
 * unreadable without the phone.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun secretStore(@ApplicationContext context: Context): SecretStore = SecretStore(context)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context, secrets: SecretStore): ClarityDatabase {
        System.loadLibrary("sqlcipher")
        // The same 32-byte key goes to sqlite3_key as with the old library, so an existing database opens as is.
        val factory = SupportOpenHelperFactory(secrets.databaseKey(), null, true)
        return Room.databaseBuilder(context, ClarityDatabase::class.java, ClarityDatabase.NAME)
            .openHelperFactory(factory)
            .addMigrations(*ClarityDatabase.MIGRATIONS)
            // Foreign keys are off by default in SQLite; the schema depends on them.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(
                object : androidx.room.RoomDatabase.Callback() {
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys = ON")
                    }
                },
            )
            .build()
    }

    @Provides fun settingsDao(db: ClarityDatabase): SettingsDao = db.settings()

    @Provides fun conversationDao(db: ClarityDatabase): ConversationDao = db.conversations()

    @Provides fun messageDao(db: ClarityDatabase): MessageDao = db.messages()

    @Provides fun cardDao(db: ClarityDatabase): CardDao = db.cards()

    @Provides fun promptOverrideDao(db: ClarityDatabase): PromptOverrideDao = db.promptOverrides()

    @Provides fun diagnosticsDao(db: ClarityDatabase): DiagnosticsDao = db.diagnostics()

    @Provides fun jobDao(db: ClarityDatabase): JobDao = db.jobs()

    @Provides fun repairDao(db: ClarityDatabase): RepairDao = db.repair()

    @Provides fun memoryDao(db: ClarityDatabase): MemoryDao = db.memory()
}
