package com.clarity.life.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import com.clarity.life.core.BackupFormat
import com.clarity.life.core.Maintenance
import com.clarity.life.settings.DefaultSettings
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.security.SecretStore
import com.clarity.life.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encrypted backups (spec section 9). The whole database is written as JSON, sealed with [BackupFormat], saved
 * to the folder he chose (Storage Access Framework, so Google Drive works), then **read back from the folder
 * and opened again** before anything is pruned; only then are older files removed, keeping the last four (or
 * as many as he chose in Settings).
 *
 * Left out, because they are rebuilt from the raw rows after a restore: the search index and the meaning
 * vectors. The API keys and the passphrase are in the Keystore, not the database, so they never go in a file.
 *
 * Restore replaces everything on this phone with the backup, in one transaction: either all of it arrives or
 * nothing changes.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val search: com.clarity.life.memory.SearchIndex,
    private val indexer: com.clarity.life.memory.EmbeddingIndexer,
    private val reminders: com.clarity.life.promise.Reminders,
) {

    sealed interface Outcome {
        data class Saved(val name: String, val at: Long) : Outcome
        data class Restored(val createdAt: Long) : Outcome
        data class Failed(val line: String) : Outcome
    }

    private val sqlite get() = db.openHelper.writableDatabase

    /** The schema version a backup carries in its header (Room's version); restore needs the same one. */
    private val schema: Int get() = sqlite.version

    // ---------- the database as JSON ----------

    /** The tables a backup carries: every table of the schema except Room's own and the derived ones. */
    private fun tables(): List<String> {
        val names = mutableListOf<String>()
        sqlite.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name").use { c ->
            while (c.moveToNext()) names.add(c.getString(0))
        }
        return names.filter { n ->
            !n.startsWith("sqlite_") && n != "android_metadata" && n != "room_master_table" &&
                !n.startsWith(DERIVED_SEARCH) && n != DERIVED_VECTORS
        }
    }

    private fun exportFilter(table: String): String? {
        val cols = columns(table)
        val kept = "(SELECT id FROM conversation WHERE off_the_record = 0)"
        val filters = buildList {
            if (table == "conversation") add("off_the_record = 0")
            if (table == "note") add("status != 'deleted_by_user'")
            if ("conversation_id" in cols) add("(conversation_id IS NULL OR conversation_id IN $kept)")
            if (table == "card") add("message_id IN (SELECT id FROM message WHERE conversation_id IN $kept)")
            if (table == "note_evidence") add("note_id IN (SELECT id FROM note WHERE status != 'deleted_by_user')")
        }
        return filters.takeIf { it.isNotEmpty() }?.joinToString(" AND ")
    }

    private fun columns(table: String): Set<String> {
        val out = mutableSetOf<String>()
        sqlite.query("PRAGMA table_info(`$table`)").use { c ->
            val i = c.getColumnIndexOrThrow("name")
            while (c.moveToNext()) out.add(c.getString(i))
        }
        return out
    }

    /**
     * Every table as JSON. [forExport] leaves out what an unencrypted export must not hold: the text of notes he
     * deleted, off-the-record conversations and everything said in them, and the app's own housekeeping.
     * Only small databases and tests should use this; backups and exports stream with [writeDump].
     */
    suspend fun dump(forExport: Boolean = false): String {
        val out = java.io.StringWriter()
        writeDump(out, forExport)
        return out.toString()
    }

    /**
     * Writes every table as JSON to [out], row by row from the cursor, so the database is never held in memory
     * (one row at a time). Read inside one transaction, so the copy is of one moment. [out] is flushed, not closed.
     */
    suspend fun writeDump(out: java.io.Writer, forExport: Boolean = false) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val w = android.util.JsonWriter(out)
            w.beginObject()
            w.name("format").value("clarity-db")
            w.name("schema").value(schema.toLong())
            w.name("tables").beginObject()
            for (t in tables()) {
                if (forExport && t in NOT_EXPORTED) continue
                val where = if (forExport) exportFilter(t) else null
                w.name(t).beginArray()
                sqlite.query("SELECT * FROM `$t`" + (where?.let { " WHERE $it" } ?: "")).use { c ->
                    while (c.moveToNext()) row(w, c)
                }
                w.endArray()
            }
            w.endObject()
            w.endObject()
            w.flush()
        }
    }

    private fun row(w: android.util.JsonWriter, c: Cursor) {
        w.beginObject()
        for (i in 0 until c.columnCount) {
            w.name(c.getColumnName(i))
            when (c.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> w.nullValue()
                Cursor.FIELD_TYPE_INTEGER -> w.value(c.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> w.value(c.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> w.beginObject().name("b64").value(Base64.getEncoder().encodeToString(c.getBlob(i))).endObject()
                else -> w.value(c.getString(i))
            }
        }
        w.endObject()
    }

    // ---------- back up ----------

    /** Back up now (and the nightly run when one is due): write, read back, verify, then prune to the last few. */
    suspend fun backUp(now: Long = System.currentTimeMillis()): Outcome = withContext(Dispatchers.IO) {
        val folder = settings.get(SettingsRepository.Keys.BACKUP_FOLDER, "").takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return@withContext record(Outcome.Failed("Choose a backup folder first."), now)
        val pass = secrets.backupPassphrase()
            ?: return@withContext record(Outcome.Failed("Set a backup passphrase first."), now)
        // A backup the phone killed half way (no finally ran) still has a backup's name, and would count as one
        // of the newest few when pruning: it goes first.
        discardUnfinished()
        var made: Uri? = null
        var saved = false
        try {
            val local = com.clarity.life.core.TimeFacts.local(Instant.ofEpochMilli(now), settings.timeZone())
            val name = BackupFormat.fileName(local)
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
            val file = DocumentsContract.createDocument(resolver, parent, MIME, name)
                ?: return@withContext record(Outcome.Failed("The folder did not accept a new file. Choose it again."), now)
            made = file
            settings.set(SettingsRepository.Keys.BACKUP_WRITING, file.toString())
            // Written as it is read from the database: rows to JSON to gzip to sealed chunks to the file.
            val written = resolver.openOutputStream(file, "w")?.use { out ->
                sealTo(out, pass, now)
            } ?: return@withContext record(Outcome.Failed("The file could not be written."), now)
            // Read back from the folder and opened, exactly as a restore would, before anything is pruned: every
            // chunk must check out and what comes out must have the checksum of what went in.
            val ok = try {
                resolver.openInputStream(file)?.use { input ->
                    val (_, json) = BackupFormat.opening(input, pass.copyOf())
                    json.use { sha256Of(it) } == written
                } ?: false
            } catch (e: BackupFormat.BadBackup) {
                false
            }
            if (!ok) {
                return@withContext record(Outcome.Failed("The backup did not read back correctly. The older ones are kept."), now)
            }
            saved = true
            settings.set(SettingsRepository.Keys.BACKUP_WRITING, "")
            prune(folder, Maintenance.keep(settings.get(SettingsRepository.Keys.BACKUP_KEEP, "").toIntOrNull(), DefaultSettings.BACKUP_KEEP))
            settings.set(SettingsRepository.Keys.LAST_BACKUP_AT, now.toString())
            record(Outcome.Saved(name, now), now)
        } catch (e: SecurityException) {
            record(Outcome.Failed("The app lost access to the backup folder. Choose it again."), now)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            record(Outcome.Failed("The backup could not be made (${e.javaClass.simpleName})."), now)
        } finally {
            pass.fill(' ')
            // Anything but a verified backup (a failed check, a full disk, the work stopped) is removed at once,
            // so a broken file never stands in for a good one.
            if (!saved && made != null) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { discardUnfinished() }
        }
    }

    /** Deletes the file of a backup that was started and never verified, if there is one. */
    private suspend fun discardUnfinished() {
        val left = settings.get(SettingsRepository.Keys.BACKUP_WRITING, "").ifBlank { return }
        val gone = try {
            DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(left))
        } catch (e: java.io.FileNotFoundException) {
            true
        } catch (e: Exception) {
            // No access right now: tried again before the next backup.
            false
        }
        if (gone) settings.set(SettingsRepository.Keys.BACKUP_WRITING, "")
    }

    /** The bytes of a backup file made now, without saving it anywhere (RestoreTest). */
    suspend fun sealNow(passphrase: CharArray, now: Long, iterations: Int = BackupFormat.ITERATIONS): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        sealTo(out, passphrase, now, iterations)
        return out.toByteArray()
    }

    /** Streams the whole database into [out] as a format 2 backup; returns the SHA-256 of the JSON written. */
    private suspend fun sealTo(
        out: java.io.OutputStream,
        passphrase: CharArray,
        now: Long,
        iterations: Int = BackupFormat.ITERATIONS,
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val sealed = BackupFormat.sealing(NotClosing(out), passphrase.copyOf(), schema, now, iterations)
        val writer = java.io.BufferedWriter(
            java.io.OutputStreamWriter(java.security.DigestOutputStream(sealed, digest), Charsets.UTF_8),
        )
        writeDump(writer)
        withContext(Dispatchers.IO) { writer.close() }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Of(input: java.io.InputStream): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BackupFormat.CHUNK)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The caller's stream stays open for its own `use`; closing the backup only finishes it. */
    private class NotClosing(out: java.io.OutputStream) : java.io.FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = out.flush()
    }

    /** Keeps the newest [keep] backups in the folder; other files there are never touched. */
    private fun prune(folder: Uri, keep: Int) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val found = mutableMapOf<String, String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) found[c.getString(1)] = c.getString(0)
        }
        for (name in BackupFormat.toPrune(found.keys.toList(), keep)) {
            val id = found[name] ?: continue
            try {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(folder, id))
            } catch (e: Exception) {
                // Pruning can wait for next week; it never fails a backup that was verified.
            }
        }
    }

    private suspend fun record(outcome: Outcome, now: Long): Outcome {
        val line = when (outcome) {
            is Outcome.Saved -> "Saved ${outcome.name}, read back and verified."
            is Outcome.Failed -> outcome.line
            is Outcome.Restored -> "Restored."
        }
        settings.set(SettingsRepository.Keys.LAST_BACKUP_LINE, line)
        if (outcome is Outcome.Failed) {
            db.diagnostics().log(ErrorLogRow(at = now, area = "backup", code = "backup_failed", detail = ""))
        }
        return outcome
    }

    // ---------- restore ----------

    /** The date a backup file was made, read from its header, before he types the passphrase. */
    suspend fun createdAt(uri: Uri): Long? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { BackupFormat.header(it).createdAt }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Restore: everything on this phone is replaced by the backup, in one transaction. The search index, the
     * meaning vectors and the reminders are rebuilt from the restored rows afterwards.
     */
    suspend fun restore(uri: Uri, passphrase: CharArray, now: Long = System.currentTimeMillis()): Outcome {
        val input = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                null
            }
        } ?: return Outcome.Failed("That file could not be read.").also { passphrase.fill(' ') }
        return input.use { restoreStream(it, passphrase, now) }
    }

    /** The restore itself, from the bytes of a backup file (RestoreTest runs it on the emulator). */
    suspend fun restoreBytes(bytes: ByteArray, passphrase: CharArray, now: Long = System.currentTimeMillis()): Outcome =
        restoreStream(java.io.ByteArrayInputStream(bytes), passphrase, now)

    /**
     * Rows go into the database as they are read from the file, inside one transaction. The transaction commits
     * only after the file was read to its very end, so every sealed chunk checked out; anything wrong on the way
     * rolls it all back and nothing on this phone changes.
     */
    private suspend fun restoreStream(input: java.io.InputStream, passphrase: CharArray, now: Long): Outcome = withContext(Dispatchers.IO) {
        try {
            val (header, json) = BackupFormat.opening(input, passphrase)
            if (header.schema > schema) {
                return@withContext Outcome.Failed("That backup is from a newer version of Clarity. Update the app first.")
            }
            if (header.schema < OLDEST_RESTORABLE) {
                return@withContext Outcome.Failed("That backup is from an older version of Clarity, which this version cannot restore.")
            }
            // The folder and the record of backups belong to this phone: the restored settings must not point at
            // the old phone's folder, nor claim a backup this phone never made.
            val keepHere = PHONE_SETTINGS.associateWith { settings.get(it, "") }
            // Alarms are set by promise id, and the restored promises reuse ids: clear this phone's first.
            val alarmsHere = db.promises().remindersAfter(0).map { it.id }
            val reader = android.util.JsonReader(java.io.BufferedReader(java.io.InputStreamReader(json, Charsets.UTF_8)))
            db.withTransaction {
                // Rows go in parent-first or child-first in any order; the foreign keys are checked at commit.
                sqlite.execSQL("PRAGMA defer_foreign_keys = ON")
                readRoot(reader)
                // Read to the end, so the last chunk (and the gzip checksum) is checked before anything commits.
                if (reader.hasMore()) throw BackupFormat.BadBackup("there is more after the data")
                drainBlank(json)
                // Every conversation rebuilds its frozen context on its next message.
                sqlite.execSQL("UPDATE conversation SET prefix_json = NULL, prefix_day = NULL")
                for ((key, value) in keepHere) {
                    if (value.isEmpty()) sqlite.execSQL("DELETE FROM settings WHERE `key` = ?", arrayOf(key)) else settings.set(key, value)
                }
            }
            // The data is in. What follows is derived and is finished even if he leaves the screen; a failure
            // here must not be reported as "nothing was changed".
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    alarmsHere.forEach(reminders::cancel)
                    reminders.syncAll(now)
                    search.rebuild()
                    indexer.schedule()
                } catch (e: Exception) {
                    db.diagnostics().log(ErrorLogRow(at = now, area = "backup", code = "restore_rebuild_failed", detail = e.javaClass.simpleName))
                }
            }
            Outcome.Restored(header.createdAt)
        } catch (e: BackupFormat.BadBackup) {
            Outcome.Failed("Could not restore: ${e.why}.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            db.diagnostics().log(ErrorLogRow(at = now, area = "backup", code = "restore_failed", detail = e.javaClass.simpleName))
            Outcome.Failed("Could not restore (${e.javaClass.simpleName}). Nothing on this phone was changed.")
        } finally {
            passphrase.fill(' ')
        }
    }

    /** True when the reader has anything but the end of the document left (it reads to the end to know). */
    private fun android.util.JsonReader.hasMore(): Boolean =
        try {
            peek() != android.util.JsonToken.END_DOCUMENT
        } catch (e: BackupFormat.BadBackup) {
            throw e
        } catch (e: Exception) {
            true
        }

    /** Whatever the reader left unread must be blank, and the stream must end (the last chunk is checked). */
    private fun drainBlank(input: java.io.InputStream) {
        val buffer = ByteArray(BackupFormat.CHUNK)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return
            for (i in 0 until n) {
                val c = buffer[i].toInt().toChar()
                if (c != ' ' && c != '\n' && c != '\r' && c != '\t') throw BackupFormat.BadBackup("there is more after the data")
            }
        }
    }

    /** {"format": "clarity-db", "schema": n, "tables": {name: [row, ...]}}, format first, as the app writes it. */
    private fun readRoot(r: android.util.JsonReader) {
        var format: String? = null
        var tablesSeen = false
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "format" -> format = r.nextString()
                "tables" -> {
                    if (format != "clarity-db") throw BackupFormat.BadBackup("that is not a Clarity backup")
                    readTables(r)
                    tablesSeen = true
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (format != "clarity-db") throw BackupFormat.BadBackup("that is not a Clarity backup")
        if (!tablesSeen) throw BackupFormat.BadBackup("that backup has no data in it")
    }

    private fun readTables(r: android.util.JsonReader) {
        val here = tables()
        for (t in here + DERIVED_VECTORS + DERIVED_SEARCH) sqlite.execSQL("DELETE FROM `$t`")
        val columnsOf = mutableMapOf<String, Set<String>>()
        r.beginObject()
        while (r.hasNext()) {
            val t = r.nextName()
            if (t !in here) throw BackupFormat.BadBackup("unknown table $t")
            val allowed = columnsOf.getOrPut(t) { columns(t) }
            r.beginArray()
            while (r.hasNext()) {
                val values = ContentValues()
                r.beginObject()
                while (r.hasNext()) {
                    val col = r.nextName()
                    if (col !in allowed) throw BackupFormat.BadBackup("unknown column $t.$col")
                    put(values, col, r)
                }
                r.endObject()
                sqlite.insert(t, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
            }
            r.endArray()
        }
        r.endObject()
    }

    private fun put(values: ContentValues, col: String, r: android.util.JsonReader) {
        when (r.peek()) {
            android.util.JsonToken.NULL -> {
                r.nextNull()
                values.putNull(col)
            }
            android.util.JsonToken.BEGIN_OBJECT -> {
                var b64: String? = null
                r.beginObject()
                while (r.hasNext()) if (r.nextName() == "b64") b64 = r.nextString() else r.skipValue()
                r.endObject()
                values.put(col, Base64.getDecoder().decode(b64.orEmpty()))
            }
            android.util.JsonToken.STRING -> values.put(col, r.nextString())
            android.util.JsonToken.NUMBER -> {
                // The number's own text decides: a whole number stays an integer, anything else is a real.
                val text = r.nextString()
                val long = text.toLongOrNull()
                if (long != null) values.put(col, long)
                else values.put(col, text.toDoubleOrNull() ?: throw BackupFormat.BadBackup("bad value in $col"))
            }
            android.util.JsonToken.BOOLEAN -> values.put(col, if (r.nextBoolean()) 1L else 0L)
            else -> throw BackupFormat.BadBackup("bad value in $col")
        }
    }

    // ---------- settings ----------

    /** Choose folder: the app keeps permission to it across restarts. */
    fun keepFolder(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    fun hasPassphrase(): Boolean = secrets.hasBackupPassphrase()

    /**
     * Test your passphrase: whether [typed] is the stored one, compared in constant time; null when none is set.
     * Both copies are wiped from memory after.
     */
    fun passphraseMatches(typed: CharArray): Boolean? {
        val stored = secrets.backupPassphrase() ?: return null.also { typed.fill(' ') }
        try {
            val a = String(stored).toByteArray(Charsets.UTF_8)
            val b = String(typed).toByteArray(Charsets.UTF_8)
            return java.security.MessageDigest.isEqual(a, b)
        } finally {
            stored.fill(' ')
            typed.fill(' ')
        }
    }

    /** True when the last verified backup is as old as he chose (weekly unless he picked daily). */
    suspend fun due(now: Long = System.currentTimeMillis()): Boolean = Maintenance.backupDue(
        settings.get(SettingsRepository.Keys.LAST_BACKUP_AT, "").toLongOrNull(),
        now,
        Maintenance.everyDays(settings.get(SettingsRepository.Keys.BACKUP_EVERY_DAYS, "").toIntOrNull(), DefaultSettings.BACKUP_EVERY_DAYS),
    )

    /** A folder and a passphrase: what the automatic backup needs. */
    suspend fun ready(): Boolean =
        hasPassphrase() && settings.get(SettingsRepository.Keys.BACKUP_FOLDER, "").isNotBlank()

    fun setPassphrase(value: CharArray) {
        require(value.size >= BackupFormat.MIN_PASSPHRASE) { "the passphrase needs at least ${BackupFormat.MIN_PASSPHRASE} characters" }
        secrets.setBackupPassphrase(value)
    }

    companion object {
        private const val MIME = "application/octet-stream"
        private const val DERIVED_SEARCH = "search_doc"
        private const val DERIVED_VECTORS = "embedding_row"

        /**
         * The oldest schema a backup may have. Every migration so far only adds (v2 added reflection_item), so
         * an older backup's rows fit this database as they are; the columns it lacks take their defaults. A
         * future migration that changes what a column means must convert old backups here, or raise this.
         */
        private const val OLDEST_RESTORABLE = 1

        /** The app's own housekeeping: no part of his life, and settings hold this phone's folder address. */
        private val NOT_EXPORTED = setOf("settings", "job", "error_log", "prompt_override")

        /** Settings that describe this phone, not his life: kept through a restore. */
        private val PHONE_SETTINGS = listOf(
            SettingsRepository.Keys.BACKUP_FOLDER,
            SettingsRepository.Keys.LAST_BACKUP_AT,
            SettingsRepository.Keys.LAST_BACKUP_LINE,
            SettingsRepository.Keys.BACKUP_WRITING,
        )
    }
}
