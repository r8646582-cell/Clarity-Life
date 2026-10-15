package com.clarity.life.memory

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.clarity.life.core.VectorMath
import com.clarity.life.data.ClarityDatabase
import com.clarity.life.data.dao.ToEmbed
import com.clarity.life.data.entity.EmbeddingRow
import com.clarity.life.data.entity.ErrorLogRow
import com.clarity.life.data.entity.SettingRow
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gives every message of his, every conversation summary and every other record search reads its meaning vector,
 * in the background, on the phone. Re-running is harmless: it only looks at rows that have no vector yet.
 */
@Singleton
class EmbeddingIndexer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: ClarityDatabase,
    private val embedder: Embedder,
    private val search: SearchIndex = SearchIndex(db, embedder),
) {

    /** Ask for the missing vectors to be made soon. Cheap to call after every message. */
    fun schedule() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            // A run that is going finishes, and one more follows for anything that arrived meanwhile.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            // Never on a low battery (Max plan Phase 5): after a model change it makes every vector again, which
            // is minutes to hours of work. Keyword search, and the vectors already made, work meanwhile.
            OneTimeWorkRequestBuilder<EmbeddingWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    /** Settings, Advanced, Re-index: every vector is thrown away and made again. */
    suspend fun reindex() {
        db.embeddings().clear()
        schedule()
    }

    /**
     * Makes the missing vectors. Returns how many, or -1 when meaning search is off on this phone. After the
     * model changed (an app update), the old model's vectors are removed first and every vector is made again;
     * until then meaning search finds only what has its new vector, and keyword search carries on as usual.
     */
    suspend fun indexMissing(now: Long = System.currentTimeMillis()): Int = withContext(Dispatchers.Default) {
        val dao = db.embeddings()
        if (db.settings().get(MODEL_KEY) != Embedder.MODEL) {
            // Once per model, not on every run: the delete reads the whole table.
            dao.deleteOtherModels(Embedder.MODEL)
            db.settings().put(SettingRow(MODEL_KEY, Embedder.MODEL))
        }
        shrinkOldVectors()
        var made = 0
        // A row the model cannot read is skipped for this run, so one bad row can never loop forever.
        val skipped = HashSet<String>()
        while (true) {
            val batch = dao.missingMessages(Embedder.MODEL, BATCH).map { SearchIndex.MESSAGE to it } +
                dao.missingSummaries(Embedder.MODEL, BATCH).map { SearchIndex.SUMMARY to it } +
                dao.missingQuotes(Embedder.MODEL, BATCH).map { SearchIndex.QUOTE to it } +
                dao.missingEvents(Embedder.MODEL, BATCH).map { SearchIndex.EVENT to ToEmbed(it.id, SearchIndex.eventText(it)) } +
                dao.missingRetiredNotes(Embedder.MODEL, BATCH).map { SearchIndex.NOTE to it } +
                dao.missingPromises(Embedder.MODEL, BATCH).map { SearchIndex.PROMISE to ToEmbed(it.id, SearchIndex.promiseText(it)) } +
                // Letters, reviews, chapters, goals, goal changes, targets and check-ins (Max plan Phase 2).
                search.recordsWithoutVectors(Embedder.MODEL, BATCH).map { it.kind to ToEmbed(it.id, it.text) }
            val todo = batch.filter { (kind, row) -> "$kind:${row.id}" !in skipped }
            if (todo.isEmpty()) break
            for ((kind, row) in todo) {
                val v = embedder.embed(row.text)
                if (v == null) {
                    val off = embedder.problem
                    db.diagnostics().log(ErrorLogRow(at = now, area = "search", code = off ?: "embed_failed", detail = ""))
                    if (off != null) return@withContext -1
                    skipped += "$kind:${row.id}"
                    continue
                }
                dao.put(EmbeddingRow(kind, row.id, Embedder.MODEL, Embedder.DIM, VectorMath.toInt8Bytes(v)))
                made++
            }
        }
        made
    }

    /**
     * Improvement report S1: vectors made before it were stored as 4 bytes a number; each becomes 1 byte a number
     * and a scale (about 134 MB to 34 MB at ten years), without running the model again. Once, a batch at a time;
     * where it got to is kept, so a stopped run carries on. Search works throughout: both forms are read.
     */
    private suspend fun shrinkOldVectors() {
        val s = db.settings()
        if (s.get(INT8_KEY) == "done") return
        val dao = db.embeddings()
        var after = s.get(INT8_KEY)?.toLongOrNull() ?: 0L
        while (true) {
            val rows = dao.vectorsAfter(Embedder.MODEL, after, SHRINK_BATCH)
            if (rows.isEmpty()) break
            // One transaction a batch: one write to the phone's storage per 500 rows, not per row.
            db.withTransaction {
                for (r in rows) {
                    if (VectorMath.isFloatForm(r.vector, Embedder.DIM)) {
                        VectorMath.fromBytes(r.vector, Embedder.DIM)?.let { dao.replaceVector(r.rowId, VectorMath.toInt8Bytes(it)) }
                    }
                }
                after = rows.last().rowId
                s.put(SettingRow(INT8_KEY, after.toString()))
            }
        }
        s.put(SettingRow(INT8_KEY, "done"))
    }

    suspend fun count(): Int = db.embeddings().count(Embedder.MODEL)

    companion object {
        private const val WORK_NAME = "embedding-index"

        /** The model the stored vectors were last cleaned up for (derived-data bookkeeping, like the index version). */
        const val MODEL_KEY = "vectors_model"
        private const val BATCH = 50

        /** Where the change to 1-byte vectors got to (a rowid), then "done". */
        const val INT8_KEY = "vectors_int8"
        private const val SHRINK_BATCH = 500
    }
}

class EmbeddingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun indexer(): EmbeddingIndexer
    }

    override suspend fun doWork(): Result {
        EntryPointAccessors.fromApplication(applicationContext, Deps::class.java).indexer().indexMissing()
        // Meaning search being off is not a failure to retry: keyword search carries on.
        return Result.success()
    }
}
